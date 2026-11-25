import { useCallback, useEffect, useState } from 'react'
import { formatearMoneda } from '../lib/pedidos'
import {
  MODOS_FALLA,
  VARIANTES_PRUEBA,
  acortarSecuencia,
  cambiarRetencion,
  cambiarShards,
  declararTopologia,
  direccionStreaming,
  eliminarStream,
  eliminarTopologia,
  formatearMs,
  inducirFallaStream,
  inspeccionarAplicacion,
  leerRegistros,
  listarAplicaciones,
  listarStreams,
  obtenerAlarma,
  obtenerInstancias,
  obtenerMetricas,
  obtenerSeguimientoCocina,
  obtenerTableroVentas,
  obtenerTendencias,
  publicarPrueba,
} from '../lib/streaming'

/** El tablero se refresca seguido para que se vea la reaccion casi en tiempo real. */
const INTERVALO_TABLERO_MS = 3000
/** La tabla de coordinacion y los streams cambian menos: se consultan mas lento. */
const INTERVALO_PLATAFORMA_MS = 6000
const STREAM_FALLIDOS = 'pedido360-fallidos'

export default function PanelStreaming() {
  const [ventas, setVentas] = useState(null)
  const [cocina, setCocina] = useState(null)
  const [tendencias, setTendencias] = useState(null)
  const [errorTablero, setErrorTablero] = useState('')

  const [streams, setStreams] = useState([])
  const [aplicaciones, setAplicaciones] = useState([])
  const [instancias, setInstancias] = useState([])
  const [alarma, setAlarma] = useState(null)
  const [metricas, setMetricas] = useState(null)
  const [fallidos, setFallidos] = useState([])
  const [errorPlataforma, setErrorPlataforma] = useState('')

  const [aviso, setAviso] = useState('')
  const [ocupado, setOcupado] = useState('')
  const [ultimaPrueba, setUltimaPrueba] = useState(null)
  const [cambios, setCambios] = useState({})

  const refrescarTablero = useCallback(async () => {
    try {
      const [v, c, t] = await Promise.all([obtenerTableroVentas(), obtenerSeguimientoCocina(), obtenerTendencias()])
      setVentas(v)
      setCocina(c)
      setTendencias(t)
      setErrorTablero('')
    } catch (error) {
      setErrorTablero(error.message)
    }
  }, [])

  const refrescarPlataforma = useCallback(async () => {
    try {
      const [s, a, i] = await Promise.all([listarStreams(), listarAplicaciones(), obtenerInstancias()])
      setStreams(s || [])
      setAplicaciones(a || [])
      setInstancias(i || [])
      setErrorPlataforma('')
    } catch (error) {
      setErrorPlataforma(error.message)
    }
  }, [])

  useEffect(() => {
    const inicio = setTimeout(refrescarTablero, 0)
    const periodico = setInterval(refrescarTablero, INTERVALO_TABLERO_MS)
    return () => {
      clearTimeout(inicio)
      clearInterval(periodico)
    }
  }, [refrescarTablero])

  useEffect(() => {
    const inicio = setTimeout(refrescarPlataforma, 0)
    const periodico = setInterval(refrescarPlataforma, INTERVALO_PLATAFORMA_MS)
    return () => {
      clearTimeout(inicio)
      clearInterval(periodico)
    }
  }, [refrescarPlataforma])

  const ejecutar = async (clave, accion, exito) => {
    setOcupado(clave)
    setAviso('')
    try {
      const resultado = await accion()
      setAviso(typeof exito === 'function' ? exito(resultado) : exito)
      await Promise.all([refrescarPlataforma(), refrescarTablero()])
    } catch (error) {
      setAviso(error.message)
    } finally {
      setOcupado('')
    }
  }

  const medirRetraso = (nombre) =>
    ejecutar(`retraso-${nombre}`, async () => {
      const detalle = await inspeccionarAplicacion(nombre)
      setAplicaciones((actuales) => actuales.map((a) => (a.nombre === nombre ? detalle : a)))
      return detalle
    }, (detalle) => `Retraso maximo de ${nombre}: ${formatearMs(detalle.retrasoMaximoMs)}`)

  const verMonitoreo = () =>
    ejecutar('monitoreo', async () => {
      const [a, m] = await Promise.all([obtenerAlarma().catch((e) => ({ error: e.message })), obtenerMetricas('pedido360-pedidos')])
      setAlarma(a)
      setMetricas(m)
    }, 'Metricas de CloudWatch actualizadas (CloudWatch tiene 1 a 2 minutos de demora).')

  const verFallidos = () =>
    ejecutar('fallidos', async () => setFallidos(await leerRegistros(STREAM_FALLIDOS, 15)),
      'Ultimos registros del stream de fallidos.')

  const instanciasVivas = instancias.filter((i) => i.alcanzable)

  return (
    <section className="mensajeria streaming" aria-label="Streaming con Kinesis">
      <p className="mensajeria-intro">
        Kinesis Data Streams: dos streams de negocio (pedidos y actividad) con 3 shards cada uno, un stream de
        fallidos y tres aplicaciones consumidoras. Consumidores en <code>{direccionStreaming}</code>.
      </p>

      {aviso && <p className="mensajeria-aviso" role="status">{aviso}</p>}

      {/* -------- 1. Tablero en vivo -------- */}
      <div className="mensajeria-bloque">
        <h3>1. Tablero en vivo (se actualiza solo con cada evento)</h3>
        {errorTablero && <p className="mensajeria-ayuda">{errorTablero}</p>}

        <div className="streaming-kpis">
          <Kpi etiqueta="Pedidos hoy" valor={ventas?.pedidosCreados ?? '—'} />
          <Kpi etiqueta="Venta del dia" valor={ventas ? formatearMoneda(ventas.montoCreado) : '—'} />
          <Kpi etiqueta="Ticket promedio" valor={ventas ? formatearMoneda(ventas.ticketPromedio) : '—'} />
          <Kpi etiqueta="Entregados" valor={ventas?.pedidosEntregados ?? '—'} />
          <Kpi etiqueta="En cocina" valor={cocina?.pedidosEnCurso ?? '—'} />
          <Kpi etiqueta="Atrasados" valor={cocina?.pedidosAtrasados ?? '—'} alerta={cocina?.pedidosAtrasados > 0} />
        </div>

        <div className="streaming-columnas">
          <div>
            <h4>Mas vendidos hoy</h4>
            <Ranking filas={(ventas?.topProductos || []).map((p) => [p.nombre, `${p.unidades} u.`])} />
          </div>
          <div>
            <h4>Cocina: pedidos en curso</h4>
            <Ranking
              filas={(cocina?.enCurso || []).slice(0, 6).map((p) => [
                `#${p.pedidoId} · ${p.estado}`,
                `${p.minutosEnEstado} min${p.atrasado ? ' ⚠' : ''}`,
              ])}
            />
            <p className="mensajeria-ayuda">
              Promedio preparacion: {cocina?.promedioMinutosPreparacion ?? '—'} min · espera:{' '}
              {cocina?.promedioMinutosEspera ?? '—'} min
            </p>
          </div>
          <div>
            <h4>Tendencias (ultima hora)</h4>
            <Ranking filas={(tendencias?.productosMasAgregados || []).map((p) => [p.etiqueta, `+${p.veces} / -${p.quitados}`])} />
            <Ranking filas={(tendencias?.busquedas || []).map((b) => [`“${b.etiqueta}”`, `${b.veces}`])} />
          </div>
        </div>
      </div>

      {/* -------- 2. Pruebas -------- */}
      <div className="mensajeria-bloque">
        <h3>2. Publicar eventos de prueba en pedido360-pedidos</h3>
        <div className="mensajeria-acciones">
          {VARIANTES_PRUEBA.map((v) => (
            <button
              key={v.id}
              type="button"
              className={`accion-${v.tono}`}
              disabled={ocupado === `prueba-${v.id}`}
              onClick={() =>
                ejecutar(`prueba-${v.id}`, async () => {
                  const r = await publicarPrueba(v.id)
                  setUltimaPrueba(r)
                  return r
                }, (r) => `Publicado: ${r.queDeberiaPasar}`)
              }
            >
              {v.etiqueta}
            </button>
          ))}
        </div>
        {ultimaPrueba && (
          <ul className="mensajeria-leyenda">
            {ultimaPrueba.publicados.map((p, i) => (
              <li key={i}>
                clave <code>{p.partitionKey}</code> → <strong>{p.shardId}</strong> seq <code>{acortarSecuencia(p.sequenceNumber)}</code>
              </li>
            ))}
          </ul>
        )}

        <h4>Fallas inducidas por aplicacion</h4>
        <div className="streaming-fallas">
          {aplicaciones.map((a) => (
            <label key={a.nombre}>
              <span>{a.nombre}</span>
              <select
                defaultValue="NINGUNA"
                onChange={(e) =>
                  ejecutar(`falla-${a.nombre}`, () => inducirFallaStream(a.nombre, e.target.value),
                    `Falla ${e.target.value} en ${a.nombre} (en todas las instancias).`)
                }
              >
                {MODOS_FALLA.map((m) => (
                  <option key={m.id} value={m.id}>{m.etiqueta}</option>
                ))}
              </select>
            </label>
          ))}
        </div>
      </div>

      {/* -------- 3. Streams -------- */}
      <div className="mensajeria-bloque">
        <h3>3. Streams</h3>
        {errorPlataforma && <p className="mensajeria-ayuda">{errorPlataforma}</p>}
        <div className="mensajeria-acciones">
          <button type="button" className="accion-primaria" disabled={ocupado === 'declarar'}
            onClick={() => ejecutar('declarar', declararTopologia, (ops) => `Topologia aplicada: ${ops.length} operaciones.`)}>
            {ocupado === 'declarar' ? 'Creando (hasta 1 min)…' : 'Crear / ajustar todo lo declarado'}
          </button>
          <button type="button" className="accion-destructiva" disabled={ocupado === 'eliminar-todo'}
            onClick={() => window.confirm('¿Eliminar los 3 streams y la alarma? Los registros se pierden.')
              && ejecutar('eliminar-todo', () => eliminarTopologia(false), 'Streams eliminados. Kinesis deja de cobrar.')}>
            Eliminar streams (fin de la sesion)
          </button>
        </div>

        <table className="streaming-tabla">
          <thead>
            <tr>
              <th>Stream</th><th>Estado</th><th>Modo</th><th>Shards</th><th>Retencion</th><th>Clave</th><th>Cambiar</th><th />
            </tr>
          </thead>
          <tbody>
            {streams.map((s) => {
              const cambio = cambios[s.nombre] || {}
              return (
                <tr key={s.nombre} title={s.justificacion || ''}>
                  <td><strong>{s.nombre}</strong></td>
                  <td>{s.estado}</td>
                  <td>{s.modoCapacidad || '—'}</td>
                  <td>{s.shardsAbiertos ?? '—'}{s.shardsDeclarados ? ` (decl. ${s.shardsDeclarados})` : ''}</td>
                  <td>{s.retencionHoras ? `${s.retencionHoras} h` : '—'}</td>
                  <td><code>{s.claveParticion || '—'}</code></td>
                  <td className="streaming-cambio">
                    <input type="number" min="1" max="10" placeholder="shards" aria-label={`Shards de ${s.nombre}`}
                      value={cambio.shards || ''}
                      onChange={(e) => setCambios({ ...cambios, [s.nombre]: { ...cambio, shards: e.target.value } })} />
                    <button type="button" className="accion-secundaria" disabled={!cambio.shards || s.estado !== 'ACTIVE'}
                      onClick={() => ejecutar(`shards-${s.nombre}`, () => cambiarShards(s.nombre, Number(cambio.shards)), (r) => r.detalle)}>
                      Reshard
                    </button>
                    <input type="number" min="24" max="8760" placeholder="horas" aria-label={`Retencion de ${s.nombre}`}
                      value={cambio.horas || ''}
                      onChange={(e) => setCambios({ ...cambios, [s.nombre]: { ...cambio, horas: e.target.value } })} />
                    <button type="button" className="accion-secundaria" disabled={!cambio.horas || s.estado !== 'ACTIVE'}
                      onClick={() => ejecutar(`retencion-${s.nombre}`, () => cambiarRetencion(s.nombre, Number(cambio.horas)), (r) => r.detalle)}>
                      Retencion
                    </button>
                  </td>
                  <td>
                    {s.estado !== 'NO_EXISTE' && (
                      <button type="button" className="accion-secundaria"
                        onClick={() => window.confirm(`¿Eliminar ${s.nombre}?`)
                          && ejecutar(`del-${s.nombre}`, () => eliminarStream(s.nombre), `${s.nombre} eliminado.`)}>
                        Eliminar
                      </button>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {/* -------- 4. Aplicaciones consumidoras -------- */}
      <div className="mensajeria-bloque">
        <h3>4. Aplicaciones consumidoras y tabla de coordinacion</h3>
        <p className="mensajeria-ayuda">
          {instanciasVivas.length} de {instancias.length} instancias de streaming-service responden. Cada fila es un
          lease: que instancia tiene el shard y hasta donde llego (checkpoint). Si una instancia se detiene, la otra
          toma sus shards al vencer el failover.
        </p>
        {aplicaciones.map((a) => (
          <div key={a.nombre} className="mensajeria-consumidor">
            <div className="mensajeria-consumidor-head">
              <strong>{a.nombre}</strong>
              <span>stream {a.stream} · retraso maximo {formatearMs(a.retrasoMaximoMs)}</span>
              <button type="button" className="accion-secundaria" disabled={ocupado === `retraso-${a.nombre}`}
                onClick={() => medirRetraso(a.nombre)}>
                Medir retraso
              </button>
            </div>
            {!a.tablaExiste ? (
              <p className="mensajeria-ayuda">Sin tabla de coordinacion: la aplicacion todavia no se ha iniciado.</p>
            ) : (
              <table className="streaming-tabla">
                <thead>
                  <tr><th>Shard</th><th>Instancia (leaseOwner)</th><th>Checkpoint</th><th>leaseCounter</th><th>Retraso</th></tr>
                </thead>
                <tbody>
                  {a.leases.map((l) => (
                    <tr key={l.shardId} title={l.nota || ''}>
                      <td>{l.shardId.replace('shardId-', '')}</td>
                      <td>{l.duenio || '— sin duenio —'}</td>
                      <td><code>{acortarSecuencia(l.checkpoint)}</code></td>
                      <td>{l.leaseCounter ?? '—'}</td>
                      <td>{formatearMs(l.retrasoMs)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ))}
      </div>

      {/* -------- 5. Monitoreo y fallidos -------- */}
      <div className="mensajeria-bloque">
        <h3>5. Monitoreo (CloudWatch) y stream de fallidos</h3>
        <div className="mensajeria-acciones">
          <button type="button" className="accion-secundaria" onClick={verMonitoreo} disabled={ocupado === 'monitoreo'}>
            Ver alarma y metricas de pedidos
          </button>
          <button type="button" className="accion-secundaria" onClick={verFallidos} disabled={ocupado === 'fallidos'}>
            Ver ultimos fallidos
          </button>
        </div>

        {alarma && (
          <div className={`mensajeria-alarma ${alarma.estado === 'ALARM' ? 'estado-alarm' : ''}`}>
            {alarma.error ? alarma.error : (
              <>
                <strong>{alarma.nombre}: {alarma.estado}</strong>
                <p>{alarma.queAnticipa}</p>
              </>
            )}
          </div>
        )}

        {metricas && (
          <div className="streaming-columnas">
            <div>
              <h4>Edad del iterador (max)</h4>
              <p>{formatearMs(ultimo(metricas.delStream['GetRecords.IteratorAgeMilliseconds']))}</p>
            </div>
            <div>
              <h4>Registros entrantes por shard ({metricas.minutos} min)</h4>
              <Ranking filas={Object.entries(metricas.porShard.IncomingRecords || {}).map(([shard, puntos]) => [shard.replace('shardId-', ''), suma(puntos)])} />
            </div>
            <div>
              <h4>Registros leidos por shard</h4>
              <Ranking filas={Object.entries(metricas.porShard.OutgoingRecords || {}).map(([shard, puntos]) => [shard.replace('shardId-', ''), suma(puntos)])} />
            </div>
          </div>
        )}

        {fallidos.length > 0 && (
          <ul className="mensajeria-dlq-lista">
            {fallidos.map((r) => {
              const f = parsear(r.contenido)
              return (
                <li key={r.sequenceNumber}>
                  <span className="mensajeria-dlq-intento">{f?.aplicacion} · {f?.tipoFalla} · intentos {f?.intentos}</span>
                  <span className="mensajeria-dlq-causa">{f?.causa}</span>
                  <code>{f?.idEvento || 'sin idEvento'} · origen {f?.shardId}</code>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </section>
  )
}

function Kpi({ etiqueta, valor, alerta }) {
  return (
    <div className={`streaming-kpi ${alerta ? 'alerta' : ''}`}>
      <span>{etiqueta}</span>
      <strong>{valor}</strong>
    </div>
  )
}

function Ranking({ filas }) {
  if (!filas.length) return <p className="mensajeria-ayuda">Sin datos todavia.</p>
  return (
    <ol className="streaming-ranking">
      {filas.map(([nombre, valor], i) => (
        <li key={`${nombre}-${i}`}><span>{nombre}</span><strong>{valor}</strong></li>
      ))}
    </ol>
  )
}

const ultimo = (puntos) => (puntos && puntos.length ? puntos[puntos.length - 1].valor : null)
const suma = (puntos) => (puntos || []).reduce((total, p) => total + p.valor, 0)

function parsear(texto) {
  try {
    return JSON.parse(texto)
  } catch {
    return null
  }
}
