export function formatCOP(value) {
  return new Intl.NumberFormat('es-CO', { style: 'currency', currency: 'COP', minimumFractionDigits: 0 }).format(value)
}

// Fecha YYYY-MM-DD en la hora LOCAL del navegador (Colombia, UTC-5). No usar
// d.toISOString().slice(0, 10): toISOString() está en UTC y después de las 7:00 p. m. ya devuelve
// el día siguiente («Ventas de hoy» en $0, gráficas corridas un día, promociones que vencen antes).
// Las fechas del backend (LocalDateTime) también vienen en hora local, así que se comparan bien.
export function fechaLocalISO(d = new Date()) {
  const dia = new Date(d)
  return `${dia.getFullYear()}-${String(dia.getMonth() + 1).padStart(2, '0')}-${String(dia.getDate()).padStart(2, '0')}`
}

export function formatDate(dateStr) {
  if (!dateStr) return '—'
  const d = new Date(dateStr + 'T00:00:00')
  return d.toLocaleDateString('es-CO', { year: 'numeric', month: 'short', day: 'numeric' })
}

// `fecha` en `ventas` es DATETIME (ej. "2026-05-10 14:32:00") — muestra fecha y hora juntas.
export function formatDateTime(dateTimeStr) {
  if (!dateTimeStr) return '—'
  const d = new Date(dateTimeStr.replace(' ', 'T'))
  const fecha = d.toLocaleDateString('es-CO', { year: 'numeric', month: 'long', day: 'numeric' })
  const hora = d.toLocaleTimeString('es-CO', { hour: 'numeric', minute: '2-digit', hour12: true })
  return `${fecha} · ${hora}`
}

// La tabla `cotizaciones` no tiene columna para la fecha deseada del servicio, solo `observaciones`
// (texto libre). En vez de agregar una columna, la fecha se empaca al inicio de `observaciones` con
// un prefijo reconocible y se vuelve a separar al mostrarla — así se ve como un campo propio en la UI
// sin tocar el esquema. Mismo patrón usado por `respuesta`/`precio_dia` en este proyecto.
// IMPORTANTE: se guarda la fecha en formato ISO (YYYY-MM-DD, el que da un <input type="date"> tal
// cual), no ya formateada en español — así el calendario de citas del admin la puede agrupar/ordenar
// como fecha real. Se formatea con formatDate() solo al mostrarla.
const PREFIJO_FECHA_DESEADA = '📅 Fecha deseada:'
const REGEX_FECHA_DESEADA = /^📅 Fecha deseada:\s*([^\n]+)\n?\n?/

export function empacarFechaDeseada(fechaIso, notaCliente) {
  const encabezado = `${PREFIJO_FECHA_DESEADA} ${fechaIso}`
  return notaCliente ? `${encabezado}\n\n${notaCliente}` : encabezado
}

export function extraerFechaDeseada(observaciones) {
  const match = (observaciones || '').match(REGEX_FECHA_DESEADA)
  return match ? match[1].trim() : null
}

export function extraerNotaCliente(observaciones) {
  return (observaciones || '').replace(REGEX_FECHA_DESEADA, '').trim() || null
}

// Mismo patrón que la fecha deseada de arriba: `ventas` no tiene columna para la dirección de
// entrega, solo `notas_cliente` (texto libre). Se empaca al inicio con un prefijo reconocible.
const PREFIJO_DIRECCION_ENTREGA = '📍 Entregar en:'
const REGEX_DIRECCION_ENTREGA = /^📍 Entregar en:\s*([^\n]+)\n?\n?/

export function empacarDireccionEntrega(direccionLegible, notaCliente) {
  const encabezado = `${PREFIJO_DIRECCION_ENTREGA} ${direccionLegible}`
  return notaCliente ? `${encabezado}\n\n${notaCliente}` : encabezado
}

export function extraerDireccionEntrega(notasCliente) {
  const match = (notasCliente || '').match(REGEX_DIRECCION_ENTREGA)
  return match ? match[1].trim() : null
}

export function extraerNotaClienteVenta(notasCliente) {
  return (notasCliente || '').replace(REGEX_DIRECCION_ENTREGA, '').trim() || null
}

// Traduce el ENUM real de `productos.unidad_medida` (BD) a una etiqueta legible en español.
const UNIDAD_MEDIDA_LABELS = {
  unidad: 'Unidad',
  metro2: 'Metro cuadrado (m²)',
  metro: 'Metro lineal',
  litro: 'Litro',
  galon: 'Galón',
  kilogramo: 'Kilogramo',
  caja: 'Caja',
  rollo: 'Rollo',
  bulto: 'Bulto',
}
export function formatUnidadMedida(valor) {
  return UNIDAD_MEDIDA_LABELS[valor] || valor
}
