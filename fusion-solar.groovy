/*
 * FusionSolar OpenAPI — Hubitat Driver (Cloud-only)
 * Versão: 0.1.0
 * Autor: RNG + ChatGPT
 */

import groovy.transform.Field
@Field static final Integer DEVLIST_MIN_REFRESH_SEC = 6 * 60 * 60   // 6h
@Field static final Integer DEVLIST_BACKOFF_SEC     = 30 * 60       // 30min quando 407

metadata {
    definition(name: "FusionSolar OpenAPI (Cloud)", namespace: "fusion", author: "You/ChatGPT") {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "PowerMeter"     // power (W) — planta ou dispositivo (quando disponível)
        capability "EnergyMeter"    // energy (kWh) — energia do dia (planta) como "energy"

        attribute "lastUpdate", "string"
        attribute "stationCodes", "string"        // CSV usado no polling
        attribute "stationListJson", "string"     // JSON de discoverStations()
        attribute "deviceListJson", "string"      // JSON de discoverDevices()
        attribute "lastApiStatus", "string"       // OK/Erro + mensagens
        attribute "lastFailCode", "number"
        attribute "rateLimitUntil", "string"      // timestamp local
        attribute "statusHtml", "string"          // cartaz de status
        attribute "plantDayEnergy", "number"      // kWh (dia)
        attribute "plantMonthEnergy", "number"    // kWh (mês)
        attribute "plantTotalEnergy", "number"    // kWh (vitalício)
        attribute "plantPowerKw", "number"        // kW (conveniência)

        command "discoverStations"
        command "discoverDevices"
        command "clearToken"
        command "forcePoll"
    }
    preferences {
        input name: "cloudBaseUrl", type: "text", title: "Base URL (ex.: https://la5.fusionsolar.huawei.com/thirdData)", required: true
        input name: "cloudUser",    type: "text", title: "Usuário (userName – Northbound)", required: true
        input name: "cloudPass",    type: "password", title: "Senha (systemCode – Northbound)", required: true

        input name: "prefStationCodes", type: "text", title: "Station codes (CSV, opcional)", required: false
        input name: "prefDevIds",       type: "text", title: "devIds (CSV, opcional)", required: false
        
        input name: "pollSeconds", type: "enum", title: "Intervalo de polling (segundos)",
              defaultValue: "60", options: ["30","60","90","120","300"]

        input name: "logEnable",  type: "bool", title: "Debug logs", defaultValue: false
        input name: "descEnable", type: "bool", title: "DescriptionText logs", defaultValue: true
    }
}

/* =========================
   Ciclo de vida
   ========================= */

def installed()  { logInfo "Instalado"; initialize() }
def updated()    { logInfo "Preferências salvas"; unschedule(); initialize() }

def initialize() {
    logDebug "initialize()"
    state.remove("token"); state.remove("cookies"); state.remove("cookieStr"); state.remove("tokenExp")
    state.remove("rateLimitUntil")
    state.devTypes = [1,10,17,38,39,41,47]  // tipos tratados no HA
    state.devTypeIdx = 0
    scheduleNext()
    refresh()
}

/* =========================
   Comandos
   ========================= */

def refresh()     { pollCloud() }
def forcePoll()   { pollCloud() }
def clearToken()  { state.remove("token"); state.remove("tokenExp"); state.remove("cookies"); state.remove("cookieStr"); logWarn "Token e cookies limpos." }
def discoverStations() { doDiscoverStations() }
def discoverDevices()  { doDiscoverDevices() }

/* =========================
   Polling (runIn)
   ========================= */

private void scheduleNext() {
    Integer s = (pollSeconds ?: "60") as Integer
    if (s < 30) s = 30
    logDebug "Agendando polling a cada ${s}s (runIn)"
    runIn(s, "pollCloud", [overwrite:true])
}

/* =========================
   Helpers de data/log
   ========================= */

private String tsNow() {
    String tzId = (location?.timeZone?.ID ?: "UTC")
    def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    sdf.setTimeZone(java.util.TimeZone.getTimeZone(tzId))
    return sdf.format(new Date())
}
private void logDebug(msg){ if (logEnable) log.debug "[FusionSolar] ${msg}" }
private void logInfo(msg){  if (descEnable) log.info  "[FusionSolar] ${msg}" }
private void logWarn(msg){  log.warn  "[FusionSolar] ${msg}" }

/* =========================
   HTTP / Token
   ========================= */

private String base() {
    String b = cloudBaseUrl?.trim()
    if (!b) return null
    return b.endsWith("/thirdData") ? b : (b.endsWith("/") ? (b + "thirdData") : (b + "/thirdData"))
}

private Map makeHeaders() {
    Map h = ["accept": "application/json", "Content-Type":"application/json", "X-Requested-With":"XMLHttpRequest"]
    if (state.token)      h["XSRF-TOKEN"] = state.token
    if (state.cookieStr)  h["Cookie"]     = state.cookieStr
    // Referer ajuda em alguns tenants
    String b = base()
    if (b) h["Referer"] = b + "/"
    return h
}

private boolean tokenValid() {
    Long exp = state.tokenExp as Long
    return (state.token && exp && now() < exp)
}

private void login() {
    String b = base()
    if (!b) { logWarn "Base URL ausente"; return }
    Map req = [ uri: "${b}/login", contentType:"application/json", requestContentType:"application/json",
                body: [ userName: cloudUser, systemCode: cloudPass ] ]
    try {
        state.remove("token"); state.remove("cookies"); state.remove("cookieStr"); state.remove("tokenExp")
        httpPost(req) { resp ->
            if (resp?.status == 200) {
                String token = null
                // headers podem vir como xsrf-token ou XSRF-TOKEN e tbm Set-Cookie
                resp?.headers?.each { h ->
                    String k = (h?.name ?: h?.key)?.toString()
                    String v = h?.value?.toString()
                    if (k?.equalsIgnoreCase("XSRF-TOKEN") || k?.equalsIgnoreCase("xsrf-token")) token = v
                }
                List<String> cookies = []
                resp?.headers?.findAll { (it?.name ?: it?.key)?.toString()?.equalsIgnoreCase("Set-Cookie") }?.each { h ->
                    def v = (h?.value ?: "") as String
                    def nv = v.tokenize(";")?.first()
                    if (nv) cookies << nv
                }
                if (token) {
                    state.token = token
                    // alguns tenants exigem também o cookie XSRF-TOKEN
                    boolean hasXsrfCookie = cookies.any { it.toLowerCase().startsWith("xsrf-token=") }
                    if (!hasXsrfCookie) cookies << "XSRF-TOKEN=${token}"
                    state.cookies = cookies
                    state.cookieStr = cookies.join("; ")
                    // validade conservadora ~25 min
                    state.tokenExp = now() + (25*60*1000)
                    sendEvent(name:"lastApiStatus", value:"login ok @ ${tsNow()}")
                    logInfo "Login ok; token e cookies obtidos."
                } else {
                    logWarn "Login ok, mas token não veio no header; headers=${(resp?.headers?:[]).collect{ (it?.name?:it?.key) }}"
                }
            } else {
                logWarn "Login falhou: HTTP ${resp?.status}"
                sendEvent(name:"lastApiStatus", value:"login http ${resp?.status}")
            }
        }
    } catch (ex) {
        logWarn "Erro no login: ${ex}"
        sendEvent(name:"lastApiStatus", value:"login erro: ${ex}")
    }
}

private Map apiPost(String path, Map body, boolean allowRetry=true) {
    String b = base()
    if (!b) { logWarn "Base URL inválida"; return [ok:false, status:-1, data:null] }
    if (!tokenValid()) login()
    Map req = [ uri: "${b}/${path}", contentType:"application/json", requestContentType:"application/json",
                headers: makeHeaders(), body: body ?: [:] ]
    logDebug "POST ${req.uri} body=${req.body} hdr=${req.headers.keySet()}"
    try {
        Map result = [ok:false, status:null, data:null, failCode:null, raw:null]
        httpPost(req) { r ->
            result.status = r?.status
            def jd = r?.data
            result.raw = jd
            // Tratamento de failCode conforme HA
            def fc = jd?.failCode
            result.failCode = fc
            if (fc == 305) {
                logWarn "Token expirado (305); relogin…"
                state.tokenExp = 0L
                if (allowRetry) {
                    login()
                    def again = apiPost(path, body, false)
                    result = again
                }
            } else if (fc == 401) {
                logWarn "401/sem permissão para interface ${path}: ${jd?.message ?: jd?.data}"
                result.ok = false
            } else if (fc == 407) {
                logWarn "Rate limit (407) em ${path}: ${jd?.data ?: 'frequency too high'}"
                // Backoff ~ uma janela de polling
                Integer s = (pollSeconds ?: "60") as Integer
                long until = now() + (s * 1000L * 2)
                state.rateLimitUntil = until
                sendEvent(name:"rateLimitUntil", value: tsFromMillis(until))
                result.ok = false
            } else if (fc != null && fc != 0) {
                logWarn "OpenAPI erro em ${path}: failCode=${fc}, msg=${jd?.data}"
                result.ok = false
            } else if (jd == null) {
                logWarn "Resposta sem JSON em ${path}"
                result.ok = false
            } else {
                result.ok = true
                result.data = jd?.data
            }
        }
        return result
    } catch (ex) {
        logWarn "HTTP erro em ${path}: ${ex}"
        return [ok:false, status:-1, data:null]
    }
}

private String tsFromMillis(long ms) {
    String tzId = (location?.timeZone?.ID ?: "UTC")
    def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    sdf.setTimeZone(java.util.TimeZone.getTimeZone(tzId))
    return sdf.format(new Date(ms))
}

/* =========================
   Descoberta (stations/devices)
   ========================= */

private void doDiscoverStations() {
    def res = apiPost("stations", [pageNo:1])
    if (!res.ok) {
        logWarn "stations falhou, tentando getStationList…"
        res = apiPost("getStationList", [:])
    }
    if (res.ok) {
        def list = []
        def data = res.data
        // "stations" retorna {data:{list:[...]}}; "getStationList" retorna {data:[...]}
        if (data?.list instanceof List) list = data.list
        else if (data instanceof List)  list = data
        def stations = list.collect { st ->
            [
                stationCode: (st.stationCode ?: st.plantCode)?.toString(),
                stationName: (st.stationName ?: st.plantName)?.toString(),
                address    : (st.stationAddr ?: st.plantAddress)?.toString(),
                capacity   : st.capacity
            ]
        }.findAll { it.stationCode }
        sendEvent(name:"stationListJson", value: groovy.json.JsonOutput.toJson(stations))
        if (stations) {
            def codes = stations*.stationCode
            sendEvent(name:"stationCodes", value: codes.join(","))
            logInfo "Plantas encontradas (${codes.size()}): ${codes}"
        } else {
            logWarn "Nenhuma planta visível para o usuário de API."
        }
    }
}

private List<String> resolveStationCodesForPolling() {
    List<String> codes = (prefStationCodes ?: device.currentValue("stationCodes") ?: "")
        .split(/\s*,\s*/).findAll{ it }.collect{ it.toString() }
    if (!codes) {
        doDiscoverStations()
        codes = (device.currentValue("stationCodes") ?: "")
           .split(/\s*,\s*/).findAll{ it }.collect{ it.toString() }
    }
    return codes
}

private void doDiscoverDevices() {
    // throttle: só atualiza se passou a janela mínima
    Long nextAt = (state.devListNextAt as Long) ?: 0L
    if (now() < nextAt) {
        logDebug "getDevList pulado: próximo discovery após ${tsFromMillis(nextAt)}"
        return
    }

    List<String> codes = resolveStationCodesForPolling()
    if (!codes) { logWarn "Sem stationCodes para getDevList"; return }

    def res = apiPost("getDevList", [stationCodes: codes.join(",")])
    if (res.ok) {
        def devices = (res.data instanceof List) ? res.data : []
        def out = devices.collect { d ->
            [devId:d.id, name:d.devName, typeId:d.devTypeId, station:d.stationCode, sn:d.esnCode,
             invType:d.invType, lat:d.latitude, lon:d.longitude]
        }
        sendEvent(name:"deviceListJson", value: groovy.json.JsonOutput.toJson(out))
        logInfo "Dispositivos: ${out.size()} encontrados."
        // agenda próxima atualização para daqui a 6h
        state.devListNextAt = now() + (DEVLIST_MIN_REFRESH_SEC * 1000L)
    } else {
        // se foi rate limit, aplica backoff; senão, tenta de novo só após a janela mínima
        if ((res.failCode as Integer) == 407) {
            state.devListNextAt = now() + (DEVLIST_BACKOFF_SEC * 1000L)
            logWarn "getDevList com 407 — novo discovery após ${tsFromMillis(state.devListNextAt as Long)}"
        } else {
            state.devListNextAt = now() + (DEVLIST_MIN_REFRESH_SEC * 1000L)
            logWarn "getDevList falhou — novo discovery após ${tsFromMillis(state.devListNextAt as Long)}"
        }
    }
}

/* =========================
   Poll principal
   ========================= */

private void pollCloud() {
    try {
        // Checa backoff de rate limit
        Long rl = state.rateLimitUntil as Long
        if (rl && now() < rl) {
            logWarn "Pulando poll por rate limit até ${tsFromMillis(rl)}"
            scheduleNext()
            return
        }

        // 1) KPIs de planta
        List<String> codes = resolveStationCodesForPolling()
        if (codes) {
            def kpi = apiPost("getStationRealKpi", [stationCodes: codes.join(",")])
            if (kpi.ok) {
                applyStationKpi(kpi.data)
            } else if (kpi.failCode == 407) {
                // rate limit já tratado
            } else if (kpi.failCode == 401) {
                logWarn "Sem permissão para getStationRealKpi — verifique permissões do usuário Northbound."
            }
        } else {
            logWarn "Sem plantas para polling. Use discoverStations ou informe Station codes nas Preferences."
        }

        // 2) KPIs de dispositivos (rotacionado por devTypeId)
        List<Map> devs = []
        String devJson = device.currentValue("deviceListJson")
        if (!devJson || devJson == "null") doDiscoverDevices()
        devJson = device.currentValue("deviceListJson")
        if (devJson && devJson != "null") {
            try { devs = new groovy.json.JsonSlurper().parseText(devJson) as List<Map> } catch(ignore){}
        }

        if (!devs?.isEmpty()) {
            // Se usuário passou devIds, usamos; senão listados
            List<String> idsInPref = (prefDevIds ?: "")
                .split(/\s*,\s*/).findAll{ it }.collect{ it.toString() }
            if (idsInPref) {
                // Quando o usuário informar devIds, precisamos do devTypeId; rotacionar por tipos conhecidos
                Integer idx = (state.devTypeIdx ?: 0) as Integer
                int typeId = (state.devTypes as List<Integer>)[idx % (state.devTypes.size())]
                state.devTypeIdx = (idx + 1) % (state.devTypes.size())
                def res = apiPost("getDevRealKpi", [devIds: idsInPref.join(","), devTypeId: typeId])
                if (res.ok) applyDeviceKpi(res.data)
            } else {
                // Agrupa por tipo e rotaciona como no HA
                Map<Integer,List<String>> byType = [:].withDefault{[]}
                devs.each { d -> if (d.typeId != null) byType[d.typeId as Integer] << d.devId?.toString() }
                if (!byType.isEmpty()) {
                    Integer idx = (state.devTypeIdx ?: 0) as Integer
                    List<Integer> types = new ArrayList<Integer>(byType.keySet())
                    int typeId = types[idx % types.size()]
                    state.devTypeIdx = (idx + 1) % (types.size())
                    def res = apiPost("getDevRealKpi", [devIds: (byType[typeId] as List<String>).join(","), devTypeId: typeId])
                    if (res.ok) applyDeviceKpi(res.data)
                }
            }
        }

        sendEvent(name:"lastUpdate", value: tsNow())
        sendEvent(name:"lastApiStatus", value:"ok @ ${tsNow()}")
        updateStatusHtml()
    } catch (ex) {
        logWarn "Erro no pollCloud: ${ex}"
        sendEvent(name:"lastApiStatus", value:"erro: ${ex}")
    } finally {
        scheduleNext()
    }
}

/* =========================
   Aplicação das métricas
   ========================= */

private void applyStationKpi(def data) {
    // data pode ser List ou Map (dependendo do endpoint/região)
    List list = (data instanceof List) ? data : (data ? [data] : [])
    if (!list) return
    // usamos a primeira planta (ou agregamos – aqui, simples)
    def s = list[0]
    // Planta -> dataItemMap com chaves: day_power, month_power, total_power (kWh) e possivelmente realTimePower (kW)
    def map = s?.dataItemMap ?: [:]
    BigDecimal day  = safeNum(map?.day_power)
    BigDecimal mon  = safeNum(map?.month_power)
    BigDecimal tot  = safeNum(map?.total_power)
    BigDecimal pkw  = safeNum(s?.realTimePower ?: map?.realTimePower) // kW
    BigDecimal pW   = (pkw != null) ? (pkw * 1000G) : null

    if (day != null) { sendEvent(name:"energy", value: day, unit:"kWh"); sendEvent(name:"plantDayEnergy", value: day, unit:"kWh") }
    if (mon != null) { sendEvent(name:"plantMonthEnergy", value: mon, unit:"kWh") }
    if (tot != null) { sendEvent(name:"plantTotalEnergy", value: tot, unit:"kWh") }
    if (pW  != null) { sendEvent(name:"power", value: pW, unit:"W"); sendEvent(name:"plantPowerKw", value:pkw, unit:"kW") }
    logInfo "Planta: P=${pW?:'-'} W | Dia=${day?:'-'} kWh | Mês=${mon?:'-'} kWh | Total=${tot?:'-'} kWh"
}

private void applyDeviceKpi(def data) {
    // Lista de objetos com campos devId e dataItemMap; mapeamos apenas o que é útil como resumo
    List list = (data instanceof List) ? data : (data ? [data] : [])
    if (!list) return
    // agregamos potência ativa (active_power) dos dispositivos suportados
    BigDecimal sumW = 0G
    boolean hasAny = false
    list.each { d ->
        def map = d?.dataItemMap ?: [:]
        if (map?.active_power != null && map.active_power != "N/A") {
            hasAny = true
            try {
                BigDecimal val = (map.active_power as BigDecimal)    // em kW para alguns tipos; em W para outros
                // Heurística: se for muito grande (ex.: > 10000), assume W; senão kW
                if (val.abs() <= 5000) sumW += (val * 1000G) else sumW += val
            } catch(ignore){}
        }
    }
    if (hasAny) {
        sendEvent(name:"power", value: sumW, unit:"W")
        sendEvent(name:"plantPowerKw", value:(sumW/1000G), unit:"kW")
        logDebug "Potência agregada por dispositivos: ${sumW} W"
    }
}

private BigDecimal safeNum(def v) {
    if (v == null) return null
    if (v instanceof Number) return v as BigDecimal
    if (v == "N/A") return null
    try { return (v as BigDecimal) } catch(ex) { return null }
}

/* =========================
   UI HTML
   ========================= */

private void updateStatusHtml() {
    // Valores básicos
    def pW   = device.currentValue("power") ?: "-"
    def day  = device.currentValue("plantDayEnergy") ?: "-"
    def mon  = device.currentValue("plantMonthEnergy") ?: "-"
    def tot  = device.currentValue("plantTotalEnergy") ?: "-"
    def ts   = device.currentValue("lastUpdate") ?: "-"
    def rate = device.currentValue("rateLimitUntil") ?: "-"

    // Obter CSV de stationCodes (do atributo ou das preferences)
    List<String> codes = []
    String csvCodesEvt = device.currentValue("stationCodes")
    String csvCodesPref = settings?.prefStationCodes
    if (csvCodesEvt && csvCodesEvt != "null") {
        codes = csvCodesEvt.split(/\s*,\s*/).findAll{ it }
    } else if (csvCodesPref) {
        codes = csvCodesPref.split(/\s*,\s*/).findAll{ it }
    }

    // Monta mapa code->name a partir do stationListJson
    Map codeToName = [:]
    String stationJson = device.currentValue("stationListJson")
    if (stationJson && stationJson != "null") {
        try {
            def list = new groovy.json.JsonSlurper().parseText(stationJson)
            if (list instanceof List) {
                list.each { st ->
                    def c = (st?.stationCode ?: st?.plantCode)?.toString()
                    def n = (st?.stationName ?: st?.plantName)?.toString()
                    if (c && n) codeToName[c] = n
                }
            }
        } catch (ignored) { /* fica em branco se não der parse */ }
    }

    // Resolve nomes na mesma ordem dos codes; se não houver codes, usa todos os nomes conhecidos; se nada, "-"
    List<String> names = []
    if (codes) {
        names = codes.collect { c -> codeToName[c] ?: c } // se não achar nome, mostra o código como fallback
    } else if (!codeToName.isEmpty()) {
        names = new ArrayList(codeToName.values())
    }
    String stationNames = names ? names.join(", ") : "-"

    String html = """
    <style>
      .fs-card{font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial;max-width:560px;border:1px solid #ddd;border-radius:12px;padding:12px;}
      .fs-row{display:flex;justify-content:space-between;border-bottom:1px dashed #eee;padding:6px 0}
      .fs-row:last-child{border-bottom:none}
      .fs-key{color:#666}
      .fs-val{font-weight:600}
      .fs-title{font-size:16px;font-weight:700;margin-bottom:8px}
      .fs-sub{font-size:12px;color:#777}
    </style>
    <div class="fs-card">
      <div class="fs-title">FusionSolar — OpenAPI</div>
      <div class="fs-row"><div class="fs-key">Potência</div><div class="fs-val">${pW} W</div></div>
      <div class="fs-row"><div class="fs-key">Energia (dia)</div><div class="fs-val">${day} kWh</div></div>
      <div class="fs-row"><div class="fs-key">Energia (mês)</div><div class="fs-val">${mon} kWh</div></div>
      <div class="fs-row"><div class="fs-key">Energia (total)</div><div class="fs-val">${tot} kWh</div></div>
      <div class="fs-row"><div class="fs-key">Estação</div><div class="fs-val">${stationNames}</div></div>
      <div class="fs-sub">Atualizado: ${ts} · Próx. backoff (se houver): ${rate}</div>
    </div>
    """
    sendEvent(name:"statusHtml", value: html)
}

