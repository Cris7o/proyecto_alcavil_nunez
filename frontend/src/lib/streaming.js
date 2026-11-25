/**
 * Cliente de la plataforma de streaming (Parcial 3).
 *
 * Igual que el panel de mensajeria, estas peticiones van directo a los
 * servicios que corren en el equipo de la presentacion (no por el API
 * Gateway): el administrador (admin-service) y las aplicaciones consumidoras
 * (streaming-service), que se levantan en dos instancias para mostrar el
 * reparto de shards.
 */
const ADMIN_URL = import.meta.env.VITE_ADMIN_URL || 'http://localhost:8080'
const STREAMING_URL = import.meta.env.VITE_STREAMING_URL || 'http://localhost:8083'

export const direccionStreaming = STREAMING_URL

async function pedir(base, ruta, opciones = {}) {
  let respuesta
  try {
    respuesta = await fetch(`${base}${ruta}`, {
      ...opciones,
      headers: { 'Content-Type': 'application/json', ...(opciones.headers || {}) },
    })
  } catch {
    throw new Error(`No se pudo contactar ${base}. Revisa que el servicio este levantado.`)
  }

  if (!respuesta.ok) {
    const texto = await respuesta.text()
    try {
      const cuerpo = JSON.parse(texto)
      throw new Error(cuerpo.detail || cuerpo.message || texto)
    } catch (error) {
      if (error instanceof SyntaxError) throw new Error(texto || `Error ${respuesta.status}`)
      throw error
    }
  }

  const tipo = respuesta.headers.get('content-type') || ''
  return tipo.includes('json') ? respuesta.json() : null
}

const admin = (ruta, opciones) => pedir(ADMIN_URL, ruta, opciones)
const consumidores = (ruta, opciones) => pedir(STREAMING_URL, ruta, opciones)
const json = (metodo, cuerpo) => ({ method: metodo, body: cuerpo ? JSON.stringify(cuerpo) : undefined })

// --- Tableros (estado derivado de los streams, en streaming-service) --------
export const obtenerTableroVentas = () => consumidores('/tablero/ventas')
export const obtenerSeguimientoCocina = () => consumidores('/tablero/cocina')
export const obtenerTendencias = () => consumidores('/tablero/tendencias')

// --- Streams (admin-service) -------------------------------------------------
export const listarStreams = () => admin('/admin/streams')
export const describirStream = (nombre) => admin(`/admin/streams/${nombre}`)
export const cambiarShards = (nombre, shards) => admin(`/admin/streams/${nombre}/shards`, json('PUT', { shards }))
export const cambiarRetencion = (nombre, horas) =>
  admin(`/admin/streams/${nombre}/retencion`, json('PUT', { horas }))
export const eliminarStream = (nombre) => admin(`/admin/streams/${nombre}`, { method: 'DELETE' })
export const declararTopologia = () => admin('/admin/streams/topologia', { method: 'POST' })
export const eliminarTopologia = (incluirTablas) =>
  admin(`/admin/streams/topologia?incluirTablas=${incluirTablas}`, { method: 'DELETE' })
export const leerRegistros = (nombre, limite = 15) => admin(`/admin/streams/${nombre}/registros?limite=${limite}`)
export const obtenerMetricas = (nombre, minutos = 30) => admin(`/admin/streams/${nombre}/metricas?minutos=${minutos}`)
export const obtenerAlarma = () => admin('/admin/streams/alarma')
export const publicarPrueba = (variante) => admin('/admin/streams/pruebas', json('POST', { variante }))

// --- Aplicaciones consumidoras (admin-service) -------------------------------
export const listarAplicaciones = () => admin('/admin/aplicaciones')
export const inspeccionarAplicacion = (nombre) => admin(`/admin/aplicaciones/${nombre}?conRetraso=true`)
export const obtenerInstancias = () => admin('/admin/aplicaciones/instancias')
export const inducirFallaStream = (aplicacion, modo) =>
  admin(`/admin/aplicaciones/${aplicacion}/falla`, json('PUT', { modo }))

export const VARIANTES_PRUEBA = [
  { id: 'VALIDO', etiqueta: 'Pedido valido', tono: 'primaria' },
  { id: 'DUPLICADO', etiqueta: 'Duplicado', tono: 'secundaria' },
  { id: 'INVALIDO', etiqueta: 'Invalido (DTO)', tono: 'destructiva' },
  { id: 'ILEGIBLE', etiqueta: 'Ilegible (no JSON)', tono: 'destructiva' },
  { id: 'INCOHERENTE', etiqueta: 'Total incoherente', tono: 'destructiva' },
]

export const MODOS_FALLA = [
  { id: 'NINGUNA', etiqueta: 'Sin falla' },
  { id: 'TRANSITORIA_RECUPERABLE', etiqueta: 'Transitoria (se recupera)' },
  { id: 'TRANSITORIA_PERSISTENTE', etiqueta: 'Transitoria (agota reintentos)' },
  { id: 'NO_RECUPERABLE', etiqueta: 'No recuperable' },
]

export function formatearMs(ms) {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} s`
  return `${(ms / 60000).toFixed(1)} min`
}

/** Los numeros de secuencia son largos: se muestra el final, que es lo que cambia. */
export function acortarSecuencia(seq) {
  if (!seq) return '—'
  return seq.length > 14 ? `…${seq.slice(-12)}` : seq
}
