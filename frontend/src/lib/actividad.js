import { apiRequest } from './api'

/**
 * Registro de la actividad del cliente en el menu (stream pedido360-actividad).
 *
 * Los eventos se juntan en el navegador y se envian por lote cada pocos
 * segundos (o al llegar a un tope), en una sola peticion al backend, que los
 * publica con PutRecords. Asi un cliente que agrega diez productos no genera
 * diez peticiones.
 *
 * Es best-effort: si el envio falla, la experiencia de compra no se ve
 * afectada. Cada evento lleva su propio idEvento, de modo que si un lote se
 * reenviara, los consumidores lo reconocerian como duplicado.
 */
const INTERVALO_ENVIO_MS = 4000
const TOPE_LOTE = 20

/** Una sesion por pestana: es la clave de particion del stream de actividad. */
function sesionActual() {
  let sesion = sessionStorage.getItem('pedido360-sesion')
  if (!sesion) {
    sesion = crypto.randomUUID()
    sessionStorage.setItem('pedido360-sesion', sesion)
  }
  return sesion
}

let pendientes = []
let temporizador = null

async function enviar() {
  temporizador = null
  if (!pendientes.length) return

  const lote = pendientes
  pendientes = []

  try {
    const resultado = await apiRequest('/actividad', {
      method: 'POST',
      body: JSON.stringify({ sesionId: sesionActual(), eventos: lote }),
    })
    if (resultado?.rechazados > 0) {
      console.warn(`Actividad: ${resultado.rechazados} de ${resultado.recibidos} eventos rechazados por Kinesis`)
    }
  } catch (error) {
    console.warn('No se pudo registrar la actividad:', error.message)
  }
}

/**
 * Registra una interaccion. tipo: PRODUCTO_AGREGADO | PRODUCTO_QUITADO |
 * BUSQUEDA | CATEGORIA_FILTRADA.
 */
export function registrarActividad(tipo, datos = {}) {
  pendientes.push({
    idEvento: crypto.randomUUID(),
    tipo,
    productoId: datos.productoId ?? null,
    productoNombre: datos.productoNombre ?? null,
    categoria: datos.categoria ?? null,
    termino: datos.termino ?? null,
    ocurridoEn: new Date().toISOString(),
  })

  if (pendientes.length >= TOPE_LOTE) {
    clearTimeout(temporizador)
    enviar()
  } else if (!temporizador) {
    temporizador = setTimeout(enviar, INTERVALO_ENVIO_MS)
  }
}
