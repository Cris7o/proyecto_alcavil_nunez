import { useState } from 'react'
import PedidoCard from '../components/PedidoCard'
import PanelMensajeria from './PanelMensajeria'
import { IconBan, IconReceipt } from '../components/Icons'
import { formatearMoneda } from '../lib/pedidos'

const PESTANAS = [
  { id: 'pedidos', etiqueta: 'Pedidos' },
  { id: 'productos', etiqueta: 'Catálogo' },
  { id: 'usuarios', etiqueta: 'Usuarios' },
  { id: 'mensajeria', etiqueta: 'Mensajería' },
]

const FORM_VACIO = { nombre: '', descripcion: '', precio: '' }

export default function PanelAdmin({
  pedidos,
  productos,
  usuarios,
  cargando,
  pedidoEnProceso,
  onCambiarEstado,
  onCrearProducto,
  onDesactivarProducto,
}) {
  const [pestana, setPestana] = useState('pedidos')
  const [form, setForm] = useState(FORM_VACIO)
  const [errorForm, setErrorForm] = useState('')
  const [guardando, setGuardando] = useState(false)
  const [confirmandoProducto, setConfirmandoProducto] = useState(null)

  const ventaTotal = pedidos
    .filter((pedido) => pedido.estado !== 'CANCELADO')
    .reduce((suma, pedido) => suma + Number(pedido.total || 0), 0)

  const entregados = pedidos.filter((pedido) => pedido.estado === 'ENTREGADO').length

  const enviarProducto = async (event) => {
    event.preventDefault()

    if (!form.nombre.trim()) {
      setErrorForm('El nombre es obligatorio.')
      return
    }

    if (!form.precio || Number(form.precio) <= 0) {
      setErrorForm('El precio debe ser mayor a 0.')
      return
    }

    setErrorForm('')
    setGuardando(true)

    try {
      await onCrearProducto({
        nombre: form.nombre.trim(),
        descripcion: form.descripcion.trim(),
        precio: Number(form.precio),
        disponible: true,
      })
      setForm(FORM_VACIO)
    } catch (error) {
      setErrorForm(error.message)
    } finally {
      setGuardando(false)
    }
  }

  return (
    <section className="panel-rol" aria-labelledby="panel-admin">
      <header className="panel-head">
        <div className="panel-head-icono" aria-hidden="true">
          <IconReceipt width={22} height={22} />
        </div>
        <div>
          <h2 id="panel-admin">Panel de administración</h2>
          <p>Ventas, catálogo y usuarios del local.</p>
        </div>
      </header>

      <div className="admin-metricas">
        <div className="metrica">
          <span>Pedidos totales</span>
          <strong>{pedidos.length}</strong>
        </div>
        <div className="metrica">
          <span>Entregados</span>
          <strong>{entregados}</strong>
        </div>
        <div className="metrica">
          <span>Ventas (sin cancelados)</span>
          <strong>{formatearMoneda(ventaTotal)}</strong>
        </div>
      </div>

      <div className="admin-tabs" role="tablist" aria-label="Secciones de administración">
        {PESTANAS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            id={`tab-${item.id}`}
            aria-selected={pestana === item.id}
            aria-controls={`panel-${item.id}`}
            className={`admin-tab ${pestana === item.id ? 'activa' : ''}`}
            onClick={() => setPestana(item.id)}
          >
            {item.etiqueta}
          </button>
        ))}
      </div>

      {pestana === 'pedidos' && (
        <div id="panel-pedidos" role="tabpanel" aria-labelledby="tab-pedidos">
          {pedidos.length === 0 ? (
            <div className="panel-vacio">
              <h3>Todavía no hay pedidos</h3>
              <p>Los pedidos de todos los clientes van a aparecer acá.</p>
            </div>
          ) : (
            <div className="pedido-lista">
              {pedidos.map((pedido) => (
                <PedidoCard
                  key={pedido.id}
                  pedido={pedido}
                  rol="ADMIN"
                  onCambiarEstado={onCambiarEstado}
                  enProceso={pedidoEnProceso === pedido.id}
                />
              ))}
            </div>
          )}
        </div>
      )}

      {pestana === 'productos' && (
        <div id="panel-productos" role="tabpanel" aria-labelledby="tab-productos">
          <form className="producto-form" onSubmit={enviarProducto}>
            <h3>Agregar producto</h3>

            <label>
              <span>Nombre</span>
              <input
                type="text"
                value={form.nombre}
                required
                onChange={(event) => setForm({ ...form, nombre: event.target.value })}
              />
            </label>

            <label>
              <span>Descripción</span>
              <input
                type="text"
                value={form.descripcion}
                onChange={(event) => setForm({ ...form, descripcion: event.target.value })}
              />
            </label>

            <label>
              <span>Precio</span>
              <input
                type="number"
                min="1"
                step="1"
                inputMode="numeric"
                value={form.precio}
                required
                onChange={(event) => setForm({ ...form, precio: event.target.value })}
              />
            </label>

            {errorForm && (
              <p className="login-error" role="alert">
                {errorForm}
              </p>
            )}

            <button type="submit" className="accion-primaria" disabled={guardando}>
              {guardando ? 'Guardando…' : 'Agregar al catálogo'}
            </button>
          </form>

          {productos.length === 0 ? (
            <div className="panel-vacio">
              <h3>Catálogo vacío</h3>
              <p>Agrega tu primer producto con el formulario de arriba.</p>
            </div>
          ) : (
            <ul className="producto-lista">
              {productos.map((producto) => (
                <li key={producto.id} className={producto.disponible ? '' : 'inactivo'}>
                  <div>
                    <strong>{producto.nombre}</strong>
                    <span>{producto.descripcion || 'Sin descripción'}</span>
                  </div>
                  <div className="producto-lista-acciones">
                    <span className="producto-precio">{formatearMoneda(producto.precio)}</span>
                    {producto.disponible ? (
                      <button
                        type="button"
                        className="accion-destructiva"
                        onClick={() => {
                          if (confirmandoProducto === producto.id) {
                            setConfirmandoProducto(null)
                            onDesactivarProducto(producto)
                          } else {
                            setConfirmandoProducto(producto.id)
                          }
                        }}
                      >
                        <IconBan />
                        {confirmandoProducto === producto.id ? 'Sí, desactivar' : 'Desactivar'}
                      </button>
                    ) : (
                      <span className="producto-inactivo">No disponible</span>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {pestana === 'usuarios' && (
        <div id="panel-usuarios" role="tabpanel" aria-labelledby="tab-usuarios">
          {usuarios.length === 0 ? (
            <div className="panel-vacio">
              <h3>Sin usuarios registrados</h3>
              <p>Los usuarios aparecen acá la primera vez que inician sesión.</p>
            </div>
          ) : (
            <ul className="usuario-lista">
              {usuarios.map((usuario) => (
                <li key={usuario.id}>
                  <div>
                    <strong>{usuario.nombre}</strong>
                    <span>{usuario.email}</span>
                  </div>
                  <span className="role-pill">{usuario.rol}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {pestana === 'mensajeria' && (
        <div id="panel-mensajeria" role="tabpanel" aria-labelledby="tab-mensajeria">
          <PanelMensajeria />
        </div>
      )}

      {cargando && <p className="panel-cargando">Actualizando datos…</p>}
    </section>
  )
}
