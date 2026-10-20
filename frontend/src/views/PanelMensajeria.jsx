import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ETIQUETAS_COLA,
  ETIQUETAS_RESULTADO,
  EVENTOS_DEMO,
  describirFiltro,
  direccionAdmin,
  formatearDuracion,
  formatearHora,
  inducirFalla,
  inspeccionarDlq,
  obtenerActividad,
  obtenerAlarmas,
  obtenerConsumidores,
  obtenerEstadoColas,
  obtenerTopologia,
  pausarConsumidor,
  publicarEventoPrueba,
  purgarDlq,
  reanudarConsumidor,
  reprocesarDlq,
} from '../lib/mensajeria'

/** Cada cuanto se refresca el estado de las colas y la actividad. */
const INTERVALO_MS = 2500

/** Las alarmas de CloudWatch se actualizan cada 5 minutos: no vale consultarlas seguido. */
const INTERVALO_ALARMAS_MS = 20000

export default function PanelMensajeria() {
  const [topologia, setTopologia] = useState(null)
  const [colas, setColas] = useState([])
  const [instancias, setInstancias] = useState([])
  const [actividad, setActividad] = useState([])
  const [alarmas, setAlarmas] = useState([])

  const [conectado, setConectado] = useState(null)
  const [errorConexion, setErrorConexion] = useState('')
  const [aviso, setAviso] = useState('')
  const [ocupado, setOcupado] = useState('')
  const [dlqAbierta, setDlqAbierta] = useState(null)
  const [mensajesDlq, setMensajesDlq] = useState([])

  /** Para poder republicar el mismo idEvento y demostrar duplicados. */
  const ultimoEvento = useRef(null)

  // ---------------------------------------------------------------------
  // Carga de datos
  // ---------------------------------------------------------------------

  const refrescar = useCallback(async () => {
    try {
      const [estadoColas, estadoInstancias, lineas] = await Promise.all([
        obtenerEstadoColas(),
        obtenerConsumidores(),
        obtenerActividad(),
      ])

      setColas(estadoColas || [])
      setInstancias(estadoInstancias || [])
      setActividad(lineas || [])
      setConectado(true)
      setErrorConexion('')
    } catch (error) {
      setConectado(false)
      setErrorConexion(error.message)
    }
  }, [])

  // La topologia se pide una sola vez: sale de configuracion y no cambia.
  useEffect(() => {
    obtenerTopologia()
      .then(setTopologia)
      .catch(() => setTopologia(null))
  }, [])

  // Consulta periodica del estado de las colas, los consumidores y la
  // actividad. El primer sondeo se agenda en lugar de ejecutarse de inmediato
  // para no actualizar el estado durante el propio renderizado.
  useEffect(() => {
    const inmediato = setTimeout(refrescar, 0)
    const periodico = setInterval(refrescar, INTERVALO_MS)

    return () => {
      clearTimeout(inmediato)
      clearInterval(periodico)
    }
  }, [refrescar])

  // Las alarmas van aparte y mas lento: CloudWatch publica las metricas de SQS
  // cada 5 minutos, asi que consultarlas cada 2 segundos no aportaria nada.
  useEffect(() => {
    const cargar = () => obtenerAlarmas().then(setAlarmas).catch(() => setAlarmas([]))

    const inmediato = setTimeout(cargar, 0)
    const periodico = setInterval(cargar, INTERVALO_ALARMAS_MS)

    return () => {
      clearTimeout(inmediato)
      clearInterval(periodico)
    }
  }, [])

  // ---------------------------------------------------------------------
  // Acciones
  // ---------------------------------------------------------------------

  const ejecutar = async (clave, accion, mensajeExito) => {
    setOcupado(clave)
    setAviso('')

    try {
      await accion()
      setAviso(mensajeExito)
      await refrescar()
    } catch (error) {
      setAviso(error.message)
    } finally {
      setOcupado('')
    }
  }

  const publicar = (tipo) =>
    ejecutar(`publicar-${tipo}`, async () => {
      const resultado = await publicarEventoPrueba(tipo)
      ultimoEvento.current = resultado
    }, `Evento ${tipo} publicado en el topico.`)

  const republicarUltimo = () => {
    const anterior = ultimoEvento.current

    if (!anterior) {
      setAviso('Primero publica un evento, despues se puede repetir.')
      return
    }

    return ejecutar(
      'republicar',
      () => publicarEventoPrueba(anterior.tipoEvento, anterior.idEvento),
      `Se repitio el evento ${anterior.idEvento}. El consumidor deberia descartarlo como duplicado.`,
    )
  }

  const abrirDlq = async (id) => {
    if (dlqAbierta === id) {
      setDlqAbierta(null)
      return
    }

    setDlqAbierta(id)
    setMensajesDlq([])

    try {
      setMensajesDlq(await inspeccionarDlq(id))
    } catch (error) {
      setAviso(error.message)
    }
  }

  // ---------------------------------------------------------------------
  // Derivados
  // ---------------------------------------------------------------------

  const colasDeclaradas = topologia?.colas || []

  const estadoDe = (nombre) => colas.find((cola) => cola.nombre === nombre)

  const consumidoresDe = (id) =>
    instancias.flatMap((entrada) => {
      if (!entrada.alcanzable || !Array.isArray(entrada.respuesta)) return []

      return entrada.respuesta
        .filter((consumidor) => consumidor.id === id)
        .map((consumidor) => ({ ...consumidor, instancia: entrada.instancia }))
    })

  const instanciasVivas = instancias.filter((entrada) => entrada.alcanzable)

  if (conectado === false) {
    return <PanelSinConexion direccion={direccionAdmin} detalle={errorConexion} />
  }

  return (
    <section className="mensajeria" aria-label="Mensajeria asincrona">
      <p className="mensajeria-intro">
        Cuando un pedido cambia de estado, el backend publica un evento en un topico de SNS. De ese
        topico cuelgan tres colas, una por cada cosa que hay que hacer, y cada una decide si le
        interesa el evento segun su filtro.
      </p>

      {aviso && (
        <p className="mensajeria-aviso" role="status">
          {aviso}
        </p>
      )}

      {/* ---------------- Publicar eventos ---------------- */}
      <div className="mensajeria-bloque">
        <h3>1. Publicar un evento</h3>
        <p className="mensajeria-ayuda">
          Mira como cambian los contadores de las tres colas segun el evento que publiques.
        </p>

        <div className="mensajeria-acciones">
          {EVENTOS_DEMO.map((evento) => (
            <button
              key={evento.tipo}
              type="button"
              className="accion-primaria"
              disabled={ocupado === `publicar-${evento.tipo}`}
              onClick={() => publicar(evento.tipo)}
              title={evento.explicacion}
            >
              {ocupado === `publicar-${evento.tipo}` ? 'Publicando...' : evento.etiqueta}
            </button>
          ))}

          <button
            type="button"
            className="accion-secundaria"
            disabled={ocupado === 'republicar'}
            onClick={republicarUltimo}
            title="Publica otra vez el mismo idEvento para demostrar la tolerancia a duplicados"
          >
            Repetir el ultimo
          </button>
        </div>

        <ul className="mensajeria-leyenda">
          {EVENTOS_DEMO.map((evento) => (
            <li key={evento.tipo}>
              <strong>{evento.etiqueta}:</strong> {evento.explicacion}
            </li>
          ))}
        </ul>
      </div>

      {/* ---------------- El topico y las tres colas ---------------- */}
      <div className="mensajeria-bloque">
        <h3>2. El topico y sus tres colas</h3>

        <div className="mensajeria-topico">
          <span className="mensajeria-topico-etiqueta">TOPICO SNS</span>
          <strong>{topologia?.topico?.nombre || 'pedido360-eventos-pedido'}</strong>
        </div>

        <div className="mensajeria-colas">
          {colasDeclaradas.map((declarada) => (
            <ColaTarjeta
              key={declarada.id}
              declarada={declarada}
              estado={estadoDe(declarada.nombre)}
              alarma={alarmas.find((item) => item.cola === declarada.dlq?.nombre)}
              abierta={dlqAbierta === declarada.id}
              mensajesDlq={mensajesDlq}
              ocupado={ocupado}
              onVerDlq={() => abrirDlq(declarada.id)}
              onReprocesar={() =>
                ejecutar(
                  `reproceso-${declarada.id}`,
                  async () => {
                    const resultado = await reprocesarDlq(declarada.id)
                    setMensajesDlq([])
                    setDlqAbierta(null)
                    return resultado
                  },
                  `Mensajes devueltos desde la DLQ de ${declarada.id} a su cola de origen.`,
                )
              }
              onPurgar={() =>
                ejecutar(
                  `purga-${declarada.id}`,
                  async () => {
                    await purgarDlq(declarada.id)
                    setMensajesDlq([])
                    setDlqAbierta(null)
                  },
                  `DLQ de ${declarada.id} purgada.`,
                )
              }
            />
          ))}
        </div>
      </div>

      {/* ---------------- Consumidores ---------------- */}
      <div className="mensajeria-bloque">
        <h3>3. Los consumidores</h3>
        <p className="mensajeria-ayuda">
          Hay {instanciasVivas.length} de {instancias.length} instancias respondiendo. Las dos leen
          las mismas colas y SQS reparte los mensajes entre ellas. Si pausas una, la otra sigue
          tomando todo: un mensaje no sale de la cola hasta que alguien lo confirma.
        </p>

        <div className="mensajeria-consumidores">
          {colasDeclaradas.map((declarada) => (
            <div key={declarada.id} className="mensajeria-consumidor">
              <div className="mensajeria-consumidor-head">
                <strong>{ETIQUETAS_COLA[declarada.id] || declarada.id}</strong>
                <div className="mensajeria-instancias">
                  {consumidoresDe(declarada.id).map((consumidor) => (
                    <span
                      key={consumidor.instancia}
                      className={`mensajeria-punto ${consumidor.activo ? 'activo' : 'pausado'}`}
                    >
                      {etiquetaInstancia(consumidor.instancia)}{' '}
                      {consumidor.activo ? 'activo' : 'pausado'}
                    </span>
                  ))}
                </div>
              </div>

              <div className="mensajeria-acciones">
                <button
                  type="button"
                  className="accion-secundaria"
                  disabled={ocupado === `pausar-${declarada.id}`}
                  onClick={() =>
                    ejecutar(
                      `pausar-${declarada.id}`,
                      () => pausarConsumidor(declarada.id),
                      `Consumidor de ${declarada.id} pausado. Los mensajes se van a acumular en la cola.`,
                    )
                  }
                >
                  Pausar
                </button>

                <button
                  type="button"
                  className="accion-secundaria"
                  disabled={ocupado === `reanudar-${declarada.id}`}
                  onClick={() =>
                    ejecutar(
                      `reanudar-${declarada.id}`,
                      () => reanudarConsumidor(declarada.id),
                      `Consumidor de ${declarada.id} reanudado. Va a procesar lo acumulado.`,
                    )
                  }
                >
                  Reanudar
                </button>

                <label className="mensajeria-falla">
                  <span className="sr-only">Falla inducida en {declarada.id}</span>
                  <select
                    value={consumidoresDe(declarada.id)[0]?.modoFalla || 'NINGUNA'}
                    onChange={(evento) =>
                      ejecutar(
                        `falla-${declarada.id}`,
                        () => inducirFalla(declarada.id, evento.target.value),
                        textoDeFalla(declarada.id, evento.target.value),
                      )
                    }
                  >
                    <option value="NINGUNA">Sin falla</option>
                    <option value="RECUPERABLE">Falla recuperable</option>
                    <option value="NO_RECUPERABLE">Falla permanente</option>
                  </select>
                </label>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* ---------------- Actividad en vivo ---------------- */}
      <div className="mensajeria-bloque">
        <h3>4. Que esta pasando ahora</h3>
        <p className="mensajeria-ayuda">
          Cada linea es un mensaje. La letra entre corchetes dice que instancia lo proceso.
        </p>

        {actividad.length === 0 ? (
          <div className="panel-vacio">
            <h3>Sin actividad todavia</h3>
            <p>Publica un evento o haz un pedido desde la app para ver el flujo.</p>
          </div>
        ) : (
          <ol className="mensajeria-actividad">
            {actividad.slice(0, 25).map((linea, indice) => (
              <li
                key={`${linea.idEvento}-${linea.consumidor}-${linea.momento}-${indice}`}
                className={`resultado-${linea.resultado?.toLowerCase()}`}
              >
                <span className="mensajeria-actividad-hora">{formatearHora(linea.momento)}</span>
                <span className="mensajeria-actividad-instancia">
                  [{etiquetaInstancia(linea.instancia)}]
                </span>
                <span className="mensajeria-actividad-cola">
                  {ETIQUETAS_COLA[linea.consumidor] || linea.consumidor}
                </span>
                <span className="mensajeria-actividad-resultado">
                  {ETIQUETAS_RESULTADO[linea.resultado] || linea.resultado}
                </span>
                <span className="mensajeria-actividad-detalle">
                  {linea.pedidoId ? `pedido #${linea.pedidoId} - ` : ''}
                  {linea.detalle}
                </span>
              </li>
            ))}
          </ol>
        )}
      </div>
    </section>
  )
}

// -------------------------------------------------------------------------
// Tarjeta de una cola
// -------------------------------------------------------------------------

function ColaTarjeta({
  declarada,
  estado,
  alarma,
  abierta,
  mensajesDlq,
  ocupado,
  onVerDlq,
  onReprocesar,
  onPurgar,
}) {
  const enDlq = estado?.dlq?.mensajesDisponibles ?? 0
  const esFanout = !declarada.suscripcion?.filtro || Object.keys(declarada.suscripcion.filtro).length === 0

  return (
    <article className={`mensajeria-cola ${enDlq > 0 ? 'con-alerta' : ''}`}>
      <header>
        <span className={`mensajeria-patron ${esFanout ? 'fanout' : 'selectivo'}`}>
          {esFanout ? 'fanout' : 'entrega selectiva'}
        </span>
        <h4>{ETIQUETAS_COLA[declarada.id] || declarada.id}</h4>
        <p className="mensajeria-cola-filtro">{describirFiltro(declarada.suscripcion?.filtro)}</p>
      </header>

      <dl className="mensajeria-contadores">
        <div>
          <dt>En cola</dt>
          <dd>{estado?.mensajesDisponibles ?? '-'}</dd>
        </div>
        <div>
          <dt>En proceso</dt>
          <dd>{estado?.mensajesEnProceso ?? '-'}</dd>
        </div>
        <div className={enDlq > 0 ? 'alerta' : ''}>
          <dt>En DLQ</dt>
          <dd>{enDlq}</dd>
        </div>
      </dl>

      <ul className="mensajeria-politicas">
        <li>
          Retencion <strong>{formatearDuracion(declarada.retencionSegundos)}</strong>
        </li>
        <li>
          Visibilidad <strong>{declarada.visibilidadSegundos} s</strong>
        </li>
        <li>
          A la DLQ al intento <strong>{declarada.maxReceiveCount}</strong>
        </li>
        <li>
          Retencion DLQ <strong>{formatearDuracion(declarada.dlq?.retencionSegundos)}</strong>
        </li>
      </ul>

      {alarma && (
        <p className={`mensajeria-alarma estado-${alarma.estado?.toLowerCase()}`}>
          Alarma CloudWatch: <strong>{textoDeAlarma(alarma.estado)}</strong>
        </p>
      )}

      <div className="mensajeria-acciones">
        <button type="button" className="accion-secundaria" onClick={onVerDlq}>
          {abierta ? 'Cerrar DLQ' : `Ver DLQ (${enDlq})`}
        </button>
      </div>

      {abierta && (
        <div className="mensajeria-dlq">
          {mensajesDlq.length === 0 ? (
            <p className="mensajeria-ayuda">La DLQ esta vacia.</p>
          ) : (
            <>
              <ul className="mensajeria-dlq-lista">
                {mensajesDlq.map((mensaje) => (
                  <li key={mensaje.messageId}>
                    <code>{mensaje.idEvento}</code>
                    <span className="mensajeria-dlq-intento">intento {mensaje.intento}</span>
                    <span className="mensajeria-dlq-causa">{mensaje.causa}</span>
                  </li>
                ))}
              </ul>

              <div className="mensajeria-acciones">
                <button
                  type="button"
                  className="accion-primaria"
                  disabled={ocupado === `reproceso-${declarada.id}`}
                  onClick={onReprocesar}
                >
                  Reprocesar
                </button>
                <button
                  type="button"
                  className="accion-destructiva"
                  disabled={ocupado === `purga-${declarada.id}`}
                  onClick={onPurgar}
                >
                  Purgar
                </button>
              </div>
            </>
          )}
        </div>
      )}
    </article>
  )
}

// -------------------------------------------------------------------------
// Sin conexion
// -------------------------------------------------------------------------

function PanelSinConexion({ direccion, detalle }) {
  return (
    <section className="mensajeria" aria-label="Mensajeria asincrona">
      <div className="panel-vacio">
        <h3>El microservicio administrador no responde</h3>
        <p>
          El panel intenta llegar a <code>{direccion}</code>, donde corren el administrador y los
          consumidores.
        </p>
        <p className="mensajeria-ayuda">
          Levantalos con <code>presentacion.bat</code> y este panel se conecta solo.
        </p>
        {detalle && <p className="mensajeria-ayuda">{detalle}</p>}
      </div>
    </section>
  )
}

// -------------------------------------------------------------------------
// Utilidades de presentacion
// -------------------------------------------------------------------------

/** De "http://localhost:8081" saca algo corto para mostrar: "A" o "B". */
function etiquetaInstancia(url) {
  if (!url) return '?'
  if (url.endsWith('8081')) return 'A'
  if (url.endsWith('8082')) return 'B'
  return url.replace(/^https?:\/\//, '')
}

function textoDeAlarma(estado) {
  if (estado === 'ALARM') return 'encendida, hay mensajes sin entregar'
  if (estado === 'OK') return 'apagada'
  return 'sin datos suficientes todavia'
}

function textoDeFalla(id, modo) {
  if (modo === 'RECUPERABLE') {
    return `El consumidor de ${id} va a fallar de forma transitoria: reintenta y, si se agota, deriva a la DLQ.`
  }
  if (modo === 'NO_RECUPERABLE') {
    return `El consumidor de ${id} va a fallar de forma permanente: deriva a la DLQ en el primer intento.`
  }
  return `El consumidor de ${id} vuelve a su comportamiento normal.`
}
