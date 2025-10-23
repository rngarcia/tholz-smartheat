/*
 * Tholz SmartHeat — Hubitat Driver (TCP)
 * Versão: 1.1 — Build: 2025-10-22
 * Base: VH / TRATO | Ajustes: RNG/ChatGPT
 * - Conexão via rawSocket (porta 4000)
 * - getDevice / setDevice
 * - Child devices para outputs
 * - Heatings (heat0) com publicação de atributos
 * - temperature prioriza t3 (tempConsumo), depois t2, t1, senão maior tN disponível
 * - Modos válidos: Off (0), Ligado (1), Automático (2), Econômico (3)
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION    = "1.1"
@Field static final String DRIVER_BUILD_DATE = "2025-10-22"
@Field static final List<String> HEAT_MODE_OPTIONS = ['Off','Ligado','Automático','Econômico']

metadata {
    definition(name: "Tholz SmartHeat", namespace: "rng", author: "RNG/ChatGPT") {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "PushableButton"
        capability "Actuator"

        // Comandos utilitários
        command "reconnect"
        // command "getDevice"

        // --- Heatings (comandos) ---
        command "heatOn"
        command "heatOff"
        command "setHeatMode", [[name:"mode*", type:"ENUM", constraints:["Off","Ligado","Automático","Econômico"]]]
        command "setHeatSetpoint", [[name:"sp(°C)*", type:"NUMBER"]]
        command "heatUp1C"
        command "heatDown1C"
        command "toggleHeatMode"
        command "showDriverInfo"

        // --- Controles dedicados (dropdown) para cada função mapeada em heat0..heat3 ---
        command "setApoioSolar",              [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]
        command "setApoioGas",                [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]
        command "setApoioEletrico",           [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]
        command "setRecirculacaoBarrilete",   [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]

        // --- Heatings (atributos no PAI) ---
        attribute "heat0Mode", "string"
        attribute "heat0On", "string"
        attribute "heat0Setpoint", "number"

        // --- Estados das funções mapeadas por canal ---
        attribute "apoioSolar", "string"              // via heat0.opMode (1=on,0=off)
        attribute "apoioGas", "string"                // via heat1.opMode
        attribute "apoioEletrico", "string"           // via heat2.opMode
        attribute "recirculacaoBarrilete", "string"   // via heat3.opMode

        // Lista de modos suportados (exposta para Rule Machine / apps)
        attribute "supportedHeatModes", "string"   // JSON string com a lista

        // Renomeações solicitadas
        attribute "tempColetor", "number"        // t1
        attribute "tempReservatorio", "number"   // t2
        attribute "tempConsumo", "number"        // t3
        attribute "temperature", "number"        // principal (prioriza t3)
        attribute "tempRecirculacao", "number"
        
        // --- Info do equipamento ---
        attribute "deviceId", "number"
        attribute "fwMain",   "string"
        attribute "fwSec",    "string"

        // --- Info do driver ---
       attribute "driverVersion", "string"
       attribute "driverBuildDate", "string"

    }

    preferences {
        input name: "device_IP_address", type: "string", title: "IP do dispositivo Tholz", required: true
        input name: "device_port", type: "number", title: "Porta TCP", defaultValue: 4000, range: "1..65535", required: true
        input name: "autoRefreshSecs", type: "number", title: "Atualizar a cada (s)", defaultValue: 15, range: "5..3600"
        input name: "logEnable", type: "bool", title: "Habilitar debug logging", defaultValue: true
    }
}

def installed() {
    logInfo "Installed — Tholz SmartHeat v${DRIVER_VERSION} (build ${DRIVER_BUILD_DATE})"
    sendEvent(name:"numberOfButtons", value:20)
    sendDriverInfo()
    publishSupportedHeatModes()
}

def updated() {
    logInfo "Updated — Tholz SmartHeat v${DRIVER_VERSION} (build ${DRIVER_BUILD_DATE})"
    sendEvent(name:"numberOfButtons", value:20)
    unschedule()
    state.remove("rxBuf")
    sendDriverInfo()
    publishSupportedHeatModes()
    initialize()
}

def initialize() {
    logInfo "Initializing — Tholz SmartHeat v${DRIVER_VERSION} (build ${DRIVER_BUILD_DATE})"
    sendDriverInfo()
    connectSocket()
    scheduleRefresh()
}

def scheduleRefresh() {
    Integer s = (settings?.autoRefreshSecs ?: 15) as Integer
    if (s < 5) s = 5
    runIn(2, "refresh")
    schedule("*/${s} * * * * ?", "refresh")
}

def reconnect() {
    logWarn "Reconnecting by user request..."
    interfaces?.rawSocket?.close()
    runIn(2, "connectSocket")
}

def showDriverInfo() { sendDriverInfo() }

private void connectSocket() {
  try {
    logInfo "Conectando em ${device_IP_address}:${device_port} ..."
    interfaces.rawSocket.connect(device_IP_address, (int) device_port)
    state.lastRxAt = now()
  } catch (e) {
    logWarn "Falha ao conectar: ${e.message}"
    runIn(10, "connectSocket")
  }
}

def uninstalled() {
    try { interfaces?.rawSocket?.close() } catch (ex) {}
}

def refresh() {
    getDevice()
}

def getDevice() {
    sendJson([command: "getDevice"])
}

def push(pushed) {
    if (pushed == null) return
    logInfo "Push ${pushed}"
    sendEvent(name:"pushed", value: pushed, isStateChange: true)

    switch (pushed) {
        case "3":  heatOn();                    break
        case "4":  heatOff();                   break
        case "5":  heatUp1C();                  break
        case "6":  heatDown1C();                break
        case "13": setHeatMode("Automático");   break
        case "14": setHeatMode("Ligado");       break
        case "15": setHeatMode("Off");          break
        case "18": toggleHeatMode();            break
        case "19": setOutputById(12, true);     break
        case "20": setOutputById(12, false);    break
        default:
            logWarn "Push ${pushed}: botão não mapeado"
    }
}

// === Toggle entre Automático e Ligado ===
def toggleHeatMode() {
    String cur = device.currentValue("heat0Mode") ?: "Off"
    String next = cur.equalsIgnoreCase("Automático") ? "Ligado" : "Automático"
    logInfo "toggleHeatMode: alternando de ${cur} para ${next}"
    setHeatMode(next)
}

// Publica a lista de modos suportados como JSON string (p/ Rule Machine enxergar via atributo)
private void publishSupportedHeatModes() {
    try {
        String json = JsonOutput.toJson(HEAT_MODE_OPTIONS)
        sendEvent(name: "supportedHeatModes", value: json, descriptionText: "Modos de aquecimento suportados atualizados")
    } catch (e) {
        logWarn "Falha ao publicar supportedHeatModes: ${e}"
    }
    }

// ======== Escrita ========

private void sendDriverInfo() {
    sendEvent(name:"driverVersion", value: DRIVER_VERSION)
    sendEvent(name:"driverBuildDate", value: DRIVER_BUILD_DATE)
    if (logEnable) log.debug "Driver info -> version=${DRIVER_VERSION}, build=${DRIVER_BUILD_DATE}"
}

private void setOutputById(Integer targetId, Boolean onVal) {
    Map last = state?.lastDevice ?: [:]
    Map outs = (last?.outputs ?: [:]) as Map

    Map argOutputs = [:]

    // 3.1) Se já conhecemos a key pelo snapshot anterior, usa
    outs?.each { k, v ->
        if (v instanceof Map && v.id == targetId) {
            argOutputs[k as String] = [on: onVal]
        }
    }

    // 3.2) Senão, tenta o chute padrão de key
    if (argOutputs.isEmpty()) {
        String guessKey = guessOutputKeyForId(targetId, outs)
        if (guessKey) {
            argOutputs[guessKey] = [on: onVal]
        }
    }

    // 3.3) Se ainda estiver vazio (porque nem outs veio), manda por ids clássicos
    if (argOutputs.isEmpty()) {
        // chutes seguros por convenção: out1->id=1 (Apoio Elétrico), out3->id=3 (Recirculação)
        String key = (targetId == 1) ? "out1" : (targetId == 3 ? "out3" : null)
        if (key) {
            argOutputs[key] = [on: onVal]
        } else {
            logWarn "Não foi possível mapear saída id=${targetId} (sem snapshot de outputs)."
        }
    }

    if (argOutputs.isEmpty()) {
        logWarn "Não foi possível montar payload para saída id=${targetId}."
        return
    }

    sendJson([command: "setDevice", argument: [outputs: argOutputs]])
}


private String guessOutputKeyForId(Integer idVal, Map outs) {
    String key = outs?.find { it?.value instanceof Map && it?.value?.id == idVal }?.key
    if (key) return key as String
    // palpites comuns
    if (idVal == 1)  return "out1"          // Apoio Elétrico
    if (idVal == 3)  return "out3"          // Recirculação
    if (idVal == 0)  return "out0"
    if (idVal == 10) return "out1"
    if (idVal == 11) return "out2"
    return null
}

// ======== Envio baixo nível ========

private void sendJson(Map obj) {
    String js = JsonOutput.toJson(obj)
    String payload = js + "\n"
    if (logEnable) log.debug "TX: ${js}"
    try {
        interfaces.rawSocket.sendMessage(payload)
    } catch (e) {
        logWarn "Falha ao envio (${e}); tentando reconectar..."
        reconnect()
    }
}

// ======== Socket callbacks ========

def parse(String description) {
    if (logEnable) log.debug "RX (String): ${description}"

    String chunk
    if (looksLikeHex(description)) {
        try {
            chunk = hexToUtf8(description)
        } catch (e) {
            log.warn "Falha ao decodificar HEX -> UTF8: ${e}"
            return
        }
    } else {
        chunk = description
    }

    state.rxBuf = (state.rxBuf ?: "") + (chunk ?: "")
    processBuffer()
}

// void parse(List<Map> description) { ... }  // (mantido, se existir no seu base)

private boolean looksLikeHex(String s) {
    if (!s) return false
    return (s ==~ /[0-9A-Fa-f]+/) && (s.length() % 2 == 0)
}

private String hexToUtf8(String hex) {
    byte[] bytes = new byte[hex.length() / 2]
    for (int i = 0; i < hex.length(); i += 2) {
        bytes[i/2] = (byte) Integer.parseInt(hex.substring(i, i+2), 16)
    }
    return new String(bytes, "UTF-8")
}

private void processBuffer() {
    String buf = state.rxBuf ?: ""
    if (!buf) return

    int level = 0
    int start = -1
    List<String> frames = []
    for (int i=0; i<buf.length(); i++) {
        char c = buf.charAt(i)
        if (c == '{') {
            if (level == 0) start = i
            level++
        } else if (c == '}') {
            level--
            if (level == 0 && start >= 0) {
                frames << buf.substring(start, i+1)
                start = -1
            }
        }
    }
    if (level == 0) {
        state.rxBuf = ""
    } else {
        state.rxBuf = (start >= 0 ? buf.substring(start) : buf)
    }

    frames.each { f -> handleJsonFrame(f) }
}

private void handleJsonFrame(String jsonText) {
    if (logEnable) log.debug "JSON frame: ${jsonText}"
    def slurper = new JsonSlurper()
    Map obj
    try {
        obj = (Map) slurper.parseText(jsonText)
    } catch (e) {
        logWarn "Falha ao parsear JSON: ${e}"
        return
    }
    if (!obj) return

    if (obj.command && obj.response instanceof Map) {
        Map resp = (Map) obj.response
        state.lastDevice = resp
        updateFromResponse(resp)
        return
    }

	if (obj.id || obj.outputs || obj.heatings) {
        state.lastDevice = obj
        updateFromResponse(obj)
        return
    }
}

private void updateFromResponse(Map resp) {
    // --------- Device Info (top-level) ----------
    if (resp?.id != null)        sendEvent(name: "deviceId", value: (resp.id as Long))
    if (resp?.firmware)          sendEvent(name: "fwMain",   value: resp.firmware as String)
    if (resp?.firmwareSec)       sendEvent(name: "fwSec",    value: resp.firmwareSec as String)

// --------- Saídas (só atualiza se vierem no payload) ----------
Map outs = (resp.outputs ?: [:]) as Map
if (outs) {
    outs.each { key, node ->
        if (!(node instanceof Map)) return
        Integer idVal = (node.id ?: -1) as Integer
        Boolean isOn  = (node.on == true)

        // child opcional (mantém se você já usa)
        String dni = "${device.id}-${key}"
        def cd = getChildDevice(dni)
        if (!cd) {
            String nice = nameForOutputId(idVal) ?: "Output ${idVal}"
            cd = addChildDevice("hubitat", "Generic Component Switch", dni,
                [label: "${device.displayName} - ${nice}", isComponent: true, componentName: key, componentLabel: nice])
            cd.updateSetting("txtEnable",[value:"true", type:"bool"])
            cd.updateDataValue("outputKey", key)
            cd.updateDataValue("outputId", "${idVal}")
        }
        componentUpdate(cd, isOn ? "on" : "off")
    }

    // guarda snapshot pro mapeamento futuro
    state.lastDevice = (state.lastDevice ?: [:]) + [outputs: outs]
}

// --------- Heatings ----------
Map heats = (resp.heatings ?: [:]) as Map
Map heat0 = heats?.heat0 as Map
if (heat0) { publishHeat0(heat0) }

// heat3.t4 -> tempRecirculacao (se já adicionamos antes)
Map heat3 = heats?.heat3 as Map
if (heat3?.t4 != null) {
    BigDecimal tRecirc = cFromRaw(heat3.t4)
    if (tRecirc != null) sendEvent(name:"tempRecirculacao", value:tRecirc, unit:"°C")
}

// --------- Estados por função (opMode: 1=Ligado / 0=Desligado) ----------
Integer h0op = (heat0?.opMode instanceof Number) ? (heat0.opMode as Integer) : null
Integer h1op = (heats?.heat1?.opMode instanceof Number) ? (heats.heat1.opMode as Integer) : null
Integer h2op = (heats?.heat2?.opMode instanceof Number) ? (heats.heat2.opMode as Integer) : null
Integer h3op = (heats?.heat3?.opMode instanceof Number) ? (heats.heat3.opMode as Integer) : null
if (h0op != null) sendEvent(name:"apoioSolar",            value: (h0op == 1 ? "on" : "off"))
if (h1op != null) sendEvent(name:"apoioGas",              value: (h1op == 1 ? "on" : "off"))
if (h2op != null) sendEvent(name:"apoioEletrico",         value: (h2op == 1 ? "on" : "off"))
if (h3op != null) sendEvent(name:"recirculacaoBarrilete", value: (h3op == 1 ? "on" : "off"))
}

private String nameForOutputId(Integer idVal) {
    switch (idVal) {
        case 0: return "Filtro"
        case 1: return "Apoio Elétrico"
        case 2: return "Apoio a Gás"
        case 3: return "Recirculação"
        case 4: return "Borbulhador"
        case 5: return "Circulação"
    }
    if (idVal >= 10 && idVal <= 19) return "Auxiliar ${(idVal-9)}"
    if (idVal >= 20 && idVal <= 29) return "Cascata ${(idVal-19)}"
    if (idVal >= 30 && idVal <= 39) return "Hidro ${(idVal-29)}"
    if (idVal >= 40 && idVal <= 59) return "Interruptor ${(idVal-39)}"
    return null
}

// ======== Component handlers (child -> parent) ========

def componentOn(cd) {
    String outKey = cd.getDataValue("outputKey")
    if (outKey) {
        sendJson([command:"setDevice", argument:[outputs:[(outKey):[on:true]]]])
        runIn(1, "getDevice")
    }
}

def componentOff(cd) {
    String outKey = cd.getDataValue("outputKey")
    if (outKey) {
        sendJson([command:"setDevice", argument:[outputs:[(outKey):[on:false]]]])
        runIn(1, "getDevice")
    }
}

private void componentUpdate(cd, String ev) {
    try {
        cd.parse([[name:"switch", value:ev, descriptionText:"${cd.displayName} was turned ${ev}"]])
    } catch (ignored) {
        if (ev == "on") cd.sendEvent(name:"switch", value:"on") else cd.sendEvent(name:"switch", value:"off")
    }
}

// ======== Heatings - comandos ========

def heatOn()  { setHeatingOnOff(true)  }
def heatOff() { setHeatingOnOff(false) }

// ======== Comandos dropdown para as funções dedicadas ========
def setApoioSolar(String estado)            { setHeatOpModeByChannel(0, mapLigadoDesligado(estado)) }
def setApoioGas(String estado)              { setHeatOpModeByChannel(1, mapLigadoDesligado(estado)) }
def setApoioEletrico(String estado)         { setHeatOpModeByChannel(2, mapLigadoDesligado(estado)) }
def setRecirculacaoBarrilete(String estado) { setHeatOpModeByChannel(3, mapLigadoDesligado(estado)) }

private Integer mapLigadoDesligado(String s) {
    String x = (s ?: "").trim().toLowerCase()
    if (x in ["ligado","on","1","true"])  return 1
    if (x in ["desligado","off","0","false"]) return 0
    logWarn "Valor inválido para estado='${s}', assumindo Desligado(0)"
    return 0
}

private void setHeatOpModeByChannel(Integer heatIdx, Integer opVal) {
    if (heatIdx == null || opVal == null) return
    String key = "heat${heatIdx}"
    Map arg = [heatings: [(key): [opMode: opVal, on: (opVal == 1)]]]
    sendJson([command:"setDevice", argument: arg])
    // Atualização otimista de atributos específicos
    switch (heatIdx) {
        case 0: sendEvent(name:"apoioSolar",            value: opVal==1 ? "on":"off"); break
        case 1: sendEvent(name:"apoioGas",              value: opVal==1 ? "on":"off"); break
        case 2: sendEvent(name:"apoioEletrico",         value: opVal==1 ? "on":"off"); break
        case 3: sendEvent(name:"recirculacaoBarrilete", value: opVal==1 ? "on":"off"); break
    }
    runIn(1, "getDevice")
}

private void setHeatingOnOff(boolean onVal) {
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    Integer typeVal = (heat0?.type   instanceof Number) ? (heat0.type   as Integer) : null
    Integer curMode = (heat0?.opMode instanceof Number) ? (heat0.opMode as Integer) : null

    Integer newMode
    if (!onVal) {
        newMode = 0
    } else {
        newMode = (curMode == null || curMode == 0) ? 1 : curMode
    }

    sendJson([command:"setDevice", argument:[heatings:[heat0:[on:onVal, opMode:newMode]]]])
    sendEvent(name:"heat0On",   value: onVal ? "on" : "off")
    if (newMode != null) sendEvent(name:"heat0Mode", value: opModeLabel(newMode, typeVal))
    runIn(1, "getDevice")
}

def setHeatMode(String mode) {
    Integer idx = opModeIndexFromLabel(mode)
    if (idx == null) { log.warn "setHeatMode: modo inválido '${mode}'"; return }
    boolean willOn = (idx != 0)
    sendJson([command:"setDevice", argument:[heatings:[heat0:[opMode:idx, on:willOn]]]])
    sendEvent(name:"heat0Mode", value: opModeLabel(idx, null))
    sendEvent(name:"heat0On",   value: willOn ? "on" : "off")
    runIn(1, "getDevice")
}

def setHeatSetpoint(Number spC) {
    if (spC == null) return
    Integer raw = rawFromC(spC)
    sendJson([command:"setDevice", argument:[heatings:[heat0:[sp:raw]]]])
    sendEvent(name:"heat0Setpoint", value: (spC as BigDecimal), unit:"°C")
    runIn(1, "getDevice")
}

def heatUp1C() {
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    BigDecimal cur = cFromRaw(heat0?.sp) ?: 0.0G
    BigDecimal min = cFromRaw(heat0?.minSp)
    BigDecimal max = cFromRaw(heat0?.maxSp)

    BigDecimal target = cur + 1.0G
    if (min != null) target = [target, min].max()
    if (max != null) target = [target, max].min()
    setHeatSetpoint(target)
}

def heatDown1C() {
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    BigDecimal cur = cFromRaw(heat0?.sp) ?: 0.0G
    BigDecimal min = cFromRaw(heat0?.minSp)
    BigDecimal max = cFromRaw(heat0?.maxSp)

    BigDecimal target = cur - 1.0G
    if (min != null) target = [target, min].max()
    if (max != null) target = [target, max].min()
    setHeatSetpoint(target)
}

// ======== Socket status ========

void socketStatus(String message) {
    logWarn "socketStatus: ${message}"
    runIn(5, "connectSocket")
}

// ======== Logging helpers ========

private void logInfo(msg){ log.info "Tholz TCP: ${msg}" }
private void logWarn(msg){ log.warn "Tholz TCP: ${msg}" }

// ======== helpers ========

private void pubParentAttr(String name, boolean onVal) {
    String v = onVal ? "on" : "off"
    sendEvent(name: name, value: v, descriptionText: "${device.displayName} ${name} is ${v}")
}

// ======== Heatings helpers ========

private BigDecimal cFromRaw(def v) {
    if (v == null) return null
    if ((v instanceof Number) && v.intValue() == 0xAAAA) return null
    try { return (v as BigDecimal) / 10.0G } catch (ignored) { return null }
}

private Integer rawFromC(def c) {
    if (c == null) return null
    BigDecimal val = (c as BigDecimal)
    return Math.round(val * 10.0G) as Integer
}

@Field Map OP_MODE_LABELS_DEFAULT = [
    0:"Off", 1:"Ligado", 2:"Automático", 3:"Econômico"
]

private String opModeLabel(Integer opMode, Integer typeVal) {
    return OP_MODE_LABELS_DEFAULT.get(opMode, "Modo ${opMode}")
}

private Integer opModeIndexFromLabel(String label) {
    String x = (label ?: "").trim().toLowerCase()
    switch (x) {
        case "off": case "desligado": return 0
        case "on": case "ligado": return 1
        case "auto": case "automático": case "automatico": return 2
        case "econômico": case "economico": return 3
    }
    try { return Integer.parseInt(x) } catch (ignored) { return null }
}

// ======== Publica atributos do heat0 (com prioridade t3 -> t2 -> t1 -> maior tN) ========

private void publishHeat0(Map h) {
    if (!(h instanceof Map)) return
    Integer typeVal = (h.type instanceof Number) ? (h.type as Integer) : null
    Integer op      = (h.opMode instanceof Number) ? (h.opMode as Integer) : null
    Boolean on      = (h.on == true)
    BigDecimal sp   = cFromRaw(h.sp)
    BigDecimal t1   = cFromRaw(h.t1)   // Coletor
    BigDecimal t2   = cFromRaw(h.t2)   // Reservatório
    BigDecimal t3   = cFromRaw(h.t3)   // Consumo (prioridade)
    BigDecimal t4   = cFromRaw(h.t4)
    BigDecimal t5   = cFromRaw(h.t5)

    // Principal: t3 -> t2 -> t1 -> maior tN disponível
    BigDecimal tMain = t3
    if (tMain == null) tMain = t2
    if (tMain == null) tMain = t1
    if (tMain == null) {
        int maxIdx = -1
        h.each { k,v ->
            def m = (k instanceof String) ? (k =~ /^t(\d+)$/) : null
            if (m && m.matches()) {
                int idx = Integer.parseInt(m[0][1])
                BigDecimal tv = cFromRaw(v)
                if (tv != null && idx > maxIdx) { maxIdx = idx; tMain = tv }
            }
        }
    }

    if (tMain != null)   sendEvent(name:"temperature",        value: tMain, unit:"°C")
    if (t1 != null)      sendEvent(name:"tempColetor",        value: t1,    unit:"°C")
    if (t2 != null)      sendEvent(name:"tempReservatorio",   value: t2,    unit:"°C")
    if (t3 != null)      sendEvent(name:"tempConsumo",        value: t3,    unit:"°C")
    else if (tMain != null) sendEvent(name:"tempConsumo",     value: tMain, unit:"°C")

    if (op != null) sendEvent(name:"heat0Mode", value: opModeLabel(op, typeVal))
    sendEvent(name:"heat0On", value: on ? "on" : "off")
    if (sp != null) sendEvent(name:"heat0Setpoint", value: sp, unit: "°C")
}
