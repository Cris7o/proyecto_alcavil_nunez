/**
 * Cliente de la API del microservicio administrador.
 *
 * A diferencia de lib/api.js, estas peticiones NO pasan por el API Gateway: el
 * administrador y los consumidores corren en el equipo desde el que se presenta,
 * porque la pauta pide mostrar dos instancias del mismo consumidor compitiendo
 * por una cola, y eso necesita dos procesos que se puedan detener a mano.
 *
 * La direccion es configurable para poder apuntar a otro equipo si hiciera
 * falta.
 */
const ADMIN_URL = import.meta.env.VITE_ADMIN_URL || 'http://localhost:8080'

/** Para que el panel pueda decirle al usuario a donde esta intentando llegar. */
export const direccionAdmin = ADMIN_URL

async function pedir(ruta, opciones = {}) {
  let respuesta

  try {
    respuesta = await fetch(`${ADMIN_URL}${ruta}`, {
      ...opciones,
      headers: { 'Content-Type': 'application/json', ...(opciones.headers || {}) },
    })
  } catch {
    // fetch solo falla asi cuando no hay nadie escuchando, o cuando el
    // navegador bloqueo la llamada. Conviene distinguirlo de un error de la API.
    throw new Error(
      `No se pudo contactar al microservicio administrador en ${ADMIN_URL}. ` +
        'Revisa que este levantado.',
    )
  }

  if (!respuesta.ok) {
    throw new Error(await mensajeDeError(respuesta))
  }

  const tipo = respuesta.headers.get('content-type') || ''
  return tipo.includes('json') ? respuesta.json() : null
}

async function mensajeDeError(respuesta) {
  const texto = await respuesta.text()

  try {
    const cuerpo = JSON.parse(texto)
    return cuerpo.detail || cuerpo.message || texto
  } catch {
    return texto || `La operacion fallo con codigo ${respuesta.status}.`
  }
}

// ---------------------------------------------------------------------------
// Topologia
// ---------------------------------------------------------------------------

/** La topologia declarada en configuracion. No consulta AWS, responde al instante. */
export const obtenerTopologia = () => pedir('/admin/topologia')

/** Aplica la topologia sobre AWS. Es idempotente. */
export const aplicarTopologia = () => pedir('/admin/topologia', { method: 'POST' })

// ---------------------------------------------------------------------------
// Colas
// ---------------------------------------------------------------------------

/** Estado en vivo de las tres colas, leido de los atributos de SQS. */
export const obtenerEstadoColas = () => pedir('/admin/colas/estado')

export const purgarCola = (nombre) => pedir(`/admin/colas/${nombre}/purga`, { method: 'POST' })

// ---------------------------------------------------------------------------
// Consumidores
// ---------------------------------------------------------------------------

export const obtenerConsumidores = () => pedir('/admin/consumidores')

export const obtenerActividad = () => pedir('/admin/consumidores/actividad')

export const pausarConsumidor = (id) =>
  pedir(`/admin/consumidores/${id}/pausar`, { method: 'POST' })

export const reanudarConsumidor = (id) =>
  pedir(`/admin/consumidores/${id}/reanudar`, { method: 'POST' })

/** modo: NINGUNA | RECUPERABLE | NO_RECUPERABLE */
export const inducirFalla = (id, modo) =>
  pedir(`/admin/consumidores/${id}/falla-inducida?modo=${modo}`, { method: 'POST' })

// ---------------------------------------------------------------------------
// Colas de mensajes muertos
// ---------------------------------------------------------------------------

export const inspeccionarDlq = (id) => pedir(`/admin/dlq/${id}?maximo=10`)

export const reprocesarDlq = (id) => pedir(`/admin/dlq/${id}/reproceso`, { method: 'POST' })

export const purgarDlq = (id) => pedir(`/admin/dlq/${id}/purga`, { method: 'POST' })

// ---------------------------------------------------------------------------
// Monitoreo
// ---------------------------------------------------------------------------

export const obtenerAlarmas = () => pedir('/admin/monitoreo/alarmas')

// ---------------------------------------------------------------------------
// Publicacion de eventos de prueba
// ---------------------------------------------------------------------------

/**
 * Publica un evento sintetico en el topico.
 *
 * Repetir el mismo idEvento es lo que demuestra la tolerancia a duplicados: el
 * consumidor procesa el primero y descarta el segundo.
 */
export const publicarEventoPrueba = (tipoEvento, idEvento) =>
  pedir('/admin/topicos/publicaciones-prueba', {
    method: 'POST',
    body: JSON.stringify({ tipoEvento, idEvento: idEvento || null, pedidoId: 0 }),
  })

// ---------------------------------------------------------------------------
// Presentacion
// ---------------------------------------------------------------------------

export const ETIQUETAS_RESULTADO = {
  PROCESADO: 'procesado',
  DUPLICADO: 'duplicado, descartado',
  REINTENTO: 'se reintentara',
  DERIVADO_A_DLQ: 'a la DLQ',
}

export const ETIQUETAS_COLA = {
  notificaciones: 'Notificaciones',
  documentos: 'Documentos',
  auditoria: 'Auditoria',
}

/**
 * Los tres eventos que vale la pena publicar en una demostracion, con lo que
 * cada uno demuestra.
 */
export const EVENTOS_DEMO = [
  {
    tipo: 'PEDIDO_CREADO',
    etiqueta: 'Pedido creado',
    explicacion: 'Pasa los tres filtros: llega a las tres colas. Esto es fanout.',
  },
  {
    tipo: 'PEDIDO_EN_PREPARACION',
    etiqueta: 'En preparacion',
    explicacion:
      'Solo auditoria lo recibe. Notificaciones y documentos lo descartan en SNS, ' +
      'antes de tocar una cola. Esto es entrega selectiva.',
  },
  {
    tipo: 'PEDIDO_ENTREGADO',
    etiqueta: 'Entregado',
    explicacion: 'Llega a notificaciones y documentos (boleta), y a auditoria.',
  },
]

/** Segundos a algo legible, para mostrar las politicas de retencion. */
export function formatearDuracion(segundos) {
  if (!segundos) return '-'

  const dias = Math.floor(segundos / 86400)
  if (dias >= 1) return `${dias} ${dias === 1 ? 'dia' : 'dias'}`

  const horas = Math.floor(segundos / 3600)
  if (horas >= 1) return `${horas} h`

  const minutos = Math.floor(segundos / 60)
  if (minutos >= 1) return `${minutos} min`

  return `${segundos} s`
}

export function formatearHora(iso) {
  if (!iso) return ''

  return new Intl.DateTimeFormat('es-CL', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(iso))
}

/** Resume el filtro de una suscripcion en una linea. */
export function describirFiltro(filtro) {
  const tipos = filtro?.tipoEvento

  if (!tipos || tipos.length === 0) {
    return 'Sin filtro: recibe todos los eventos'
  }

  return tipos.map((tipo) => tipo.replace('PEDIDO_', '')).join(', ')
}
