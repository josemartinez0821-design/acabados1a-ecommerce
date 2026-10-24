<script setup>
// RF01 - registro de usuarios (nombre, correo, contraseña, teléfono, dirección) -> tabla `usuarios`
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useToast } from '../composables/useToast'
import { useValidationTooltip } from '../composables/useValidationTooltip'
import { DEPARTAMENTOS, MUNICIPIOS_POR_DEPARTAMENTO } from '../data/colombia'
import api from '../services/api'
import logoUrl from '../assets/logo.png'

// Borrador del formulario: al hacer clic en "términos y condiciones" o "política de privacidad"
// (RouterLink normal, no target="_blank") Vue destruye este componente por completo y lo vuelve a
// crear al volver — sin esto, cualquier dato ya escrito se perdía. Se guarda en sessionStorage
// (se borra solo al cerrar la pestaña) y a propósito NO se guardan las contraseñas ahí por
// seguridad; esas sí tocará volver a escribirlas.
const CLAVE_BORRADOR = 'acabados1a_registro_borrador'
function cargarBorrador() {
  try { return JSON.parse(sessionStorage.getItem(CLAVE_BORRADOR) || 'null') } catch { return null }
}
function limpiarBorrador() {
  sessionStorage.removeItem(CLAVE_BORRADOR)
}

const form = ref({
  nombreCompleto: '',
  tipoIdentificacion: 'CC',
  numeroIdentificacion: '',
  email: '',
  telefono: '',
  direccion: '',
  ciudad: '',
  departamento: 'Huila',
  password: '',
  confirmarPassword: '',
  aceptaTerminos: false,
  ...cargarBorrador(),
})

// Cualquier cambio en los datos (menos las contraseñas) se guarda al toque.
watch(
  form,
  (v) => {
    const { password, confirmarPassword, ...datosNoSensibles } = v
    sessionStorage.setItem(CLAVE_BORRADOR, JSON.stringify(datosNoSensibles))
  },
  { deep: true }
)

const mostrarPassword = ref(false)
const mostrarConfirmarPassword = ref(false)
const error = ref('')
const cargando = ref(false)
const { marcarError } = useValidationTooltip()

// Contraseña, confirmar contraseña y el checkbox de términos no tienen (o no pueden tener) un
// equivalente de validación HTML5 nativa fuerte - password/confirmarPassword solo llevan
// `required` (no detecta "débil" ni "no coincide"), y el checkbox no lleva `required` - por eso
// estos tres checks de registrar() sí son alcanzables y necesitan su propio scroll+foco. El resto
// de campos del formulario (nombre, documento, teléfono, ciudad, departamento) sí tienen
// `required` real y el navegador los bloquea antes de que el submit llegue a esta función.
const passwordEl = ref(null)
const confirmarPasswordEl = ref(null)
const terminosCheckboxEl = ref(null)

// paso 1 = formulario, paso 2 = verificar el código que se manda al correo apenas se crea la
// cuenta (misma cuenta ya queda creada en el paso 1, solo que sin poder iniciar sesión todavía).
const paso = ref(1)
const codigoDigitos = ref(['', '', '', '', '', ''])
const codigoInputs = ref([])
const reenviarSegundos = ref(60)
let reenviarTimer = null

function iniciarConteoReenvio() {
  reenviarSegundos.value = 60
  clearInterval(reenviarTimer)
  reenviarTimer = setInterval(() => {
    reenviarSegundos.value -= 1
    if (reenviarSegundos.value <= 0) clearInterval(reenviarTimer)
  }, 1000)
}
function onCodigoInput(idx, evento) {
  const valor = evento.target.value.replace(/\D/g, '').slice(-1)
  codigoDigitos.value[idx] = valor
  if (valor && idx < 5) codigoInputs.value[idx + 1]?.focus()
}
function onCodigoKeydown(idx, evento) {
  if (evento.key === 'Backspace' && !codigoDigitos.value[idx] && idx > 0) {
    codigoInputs.value[idx - 1]?.focus()
  }
}
function onCodigoPaste(evento) {
  const pegado = (evento.clipboardData.getData('text') || '').replace(/\D/g, '').slice(0, 6)
  evento.preventDefault()
  pegado.split('').forEach((c, i) => (codigoDigitos.value[i] = c))
  nextTick(() => codigoInputs.value[Math.min(pegado.length, 5)]?.focus())
}

const municipiosDisponibles = computed(() => MUNICIPIOS_POR_DEPARTAMENTO[form.value.departamento] || [])

watch(() => form.value.departamento, () => {
  if (!municipiosDisponibles.value.includes(form.value.ciudad)) {
    form.value.ciudad = ''
  }
})

// Número de documento: solo dígitos. Letras, puntos, guiones o espacios se quitan apenas se
// escriben o se pegan, así nunca llegan al formulario ni a la BD.
watch(() => form.value.numeroIdentificacion, (valor) => {
  const soloDigitos = valor.replace(/\D/g, '')
  if (soloDigitos !== valor) form.value.numeroIdentificacion = soloDigitos
})

// Duplicados en tiempo real: apenas escriben un número de identificación, teléfono o correo que ya
// existe, se los marcamos ahí mismo junto al campo — no hay que esperar a enviar el formulario para
// enterarse. Antes comparaba contra una lista fija de usuarios de prueba (MockData); ahora
// consulta GET /api/auth/disponibilidad (público) contra la BD real, con un pequeño debounce para
// no disparar una petición por cada tecla. Es solo una ayuda visual — el backend vuelve a
// validar de verdad al enviar el formulario, así que un fallo de red aquí no deja pasar nada.
const cedulaDuplicada = ref(false)
const telefonoDuplicado = ref(false)
const emailDuplicado = ref(false)
let timeoutDisponibilidad = null

watch(() => [form.value.numeroIdentificacion, form.value.telefono, form.value.email], () => {
  const cedula = form.value.numeroIdentificacion.trim()
  const telefono = form.value.telefono.trim()
  const email = form.value.email.trim().toLowerCase()
  const emailValido = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)

  if (!cedula) cedulaDuplicada.value = false
  if (!telefono) telefonoDuplicado.value = false
  if (!email || !emailValido) emailDuplicado.value = false

  clearTimeout(timeoutDisponibilidad)
  if (!cedula && !telefono && !(email && emailValido)) return
  timeoutDisponibilidad = setTimeout(async () => {
    const params = {}
    if (cedula) params.numero_identificacion = cedula
    if (telefono) params.telefono = telefono
    if (email && emailValido) params.email = email
    try {
      const { data } = await api.get('/auth/disponibilidad', { params })
      if ('numero_identificacion' in data) cedulaDuplicada.value = data.numero_identificacion
      if ('telefono' in data) telefonoDuplicado.value = data.telefono
      if ('email' in data) emailDuplicado.value = data.email
    } catch {
      // silencioso a propósito - ver comentario arriba
    }
  }, 400)
})

const requisitos = computed(() => ({
  longitud: form.value.password.length >= 8,
  mayuscula: /[A-Z]/.test(form.value.password),
  numero: /\d/.test(form.value.password),
}))
const passwordValida = computed(() => requisitos.value.longitud && requisitos.value.mayuscula && requisitos.value.numero)

const fuerzaPassword = computed(() => {
  const pass = form.value.password
  if (!pass) return null
  let score = 0
  if (pass.length >= 8) score++
  if (pass.length >= 12) score++
  if (/[A-Z]/.test(pass)) score++
  if (/[a-z]/.test(pass)) score++
  if (/\d/.test(pass)) score++
  if (/[^A-Za-z0-9]/.test(pass)) score++
  if (score <= 2) return { nivel: 'debil', label: 'Débil' }
  if (score <= 4) return { nivel: 'media', label: 'Media' }
  return { nivel: 'fuerte', label: 'Fuerte' }
})

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { showToast } = useToast()

// Desde LoginView.vue, "Reenviar código" (cuando la cuenta existe pero no está verificada) llega
// aquí con ?verificar=correo — salta directo al paso 2 en vez de pedir llenar el formulario de nuevo.
onMounted(() => {
  if (route.query.verificar) {
    form.value.email = String(route.query.verificar)
    paso.value = 2
    iniciarConteoReenvio()
  }
})

async function registrar() {
  error.value = ''

  const nombreCompleto = form.value.nombreCompleto.trim()
  if (!nombreCompleto) {
    error.value = 'Ingresa tu nombre completo.'
    return
  }
  if (!form.value.numeroIdentificacion.trim()) {
    error.value = 'Ingresa tu número de identificación.'
    return
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.value.email)) {
    error.value = 'Ingresa un correo electrónico válido.'
    return
  }
  if (!form.value.telefono.trim()) {
    error.value = 'Ingresa tu teléfono.'
    return
  }
  if (!form.value.ciudad.trim()) {
    error.value = 'Ingresa tu ciudad.'
    return
  }
  if (!form.value.departamento) {
    error.value = 'Selecciona tu departamento.'
    return
  }
  if (!passwordValida.value) {
    marcarError(passwordEl.value, 'La contraseña debe tener mínimo 8 caracteres, una mayúscula y un número.')
    return
  }
  if (form.value.password !== form.value.confirmarPassword) {
    marcarError(confirmarPasswordEl.value, 'Las contraseñas no coinciden.')
    return
  }
  if (!form.value.aceptaTerminos) {
    marcarError(terminosCheckboxEl.value, 'Debes aceptar los términos y condiciones y la política de privacidad.')
    return
  }

  cargando.value = true

  const [nombre, ...resto] = nombreCompleto.split(' ')
  const apellido = resto.join(' ')
  try {
    await api.post('/auth/registro', {
      nombre,
      apellido,
      tipo_identificacion: form.value.tipoIdentificacion,
      numero_identificacion: form.value.numeroIdentificacion.trim(),
      email: form.value.email,
      telefono: form.value.telefono,
      whatsapp: form.value.telefono,
      direccion: form.value.direccion,
      ciudad: form.value.ciudad,
      departamento: form.value.departamento,
      password: form.value.password,
    })
  } catch (e) {
    error.value = e.response?.data?.mensaje || 'No se pudo crear la cuenta. Intenta de nuevo.'
    cargando.value = false
    return
  }

  cargando.value = false
  limpiarBorrador()
  showToast('¡Cuenta creada! Revisa tu correo para verificarla.', 'success')
  paso.value = 2
  iniciarConteoReenvio()
  nextTick(() => codigoInputs.value[0]?.focus())
}

async function verificarCodigo() {
  error.value = ''
  const codigo = codigoDigitos.value.join('')
  if (codigo.length < 6) {
    marcarError(codigoInputs.value[0], 'Ingresa los 6 dígitos del código.')
    return
  }

  cargando.value = true
  try {
    const { data } = await api.post('/auth/verificar-email', { email: form.value.email, codigo })
    auth.setSession(data.token, data.usuario)
  } catch (e) {
    error.value = e.response?.data?.mensaje || 'El código ingresado no es válido.'
    cargando.value = false
    return
  }

  cargando.value = false
  showToast(`¡Cuenta verificada! Bienvenido, ${form.value.nombreCompleto.split(' ')[0]}.`, 'success')
  router.push('/')
}

async function reenviarCodigo() {
  try {
    await api.post('/auth/reenviar-codigo', { email: form.value.email })
    showToast('Te enviamos un nuevo código a tu correo.', 'info')
  } catch (e) {
    showToast(e.response?.data?.mensaje || 'No se pudo reenviar el código.', 'danger')
    return
  }
  codigoDigitos.value = ['', '', '', '', '', '']
  iniciarConteoReenvio()
  nextTick(() => codigoInputs.value[0]?.focus())
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-visual">
      <div class="auth-visual-bg">
        <img src="https://images.unsplash.com/photo-1562259929-b4e1fd3aef09?w=1200&q=80" alt="Acabados y Diseños 1A" />
        <div class="auth-visual-overlay"></div>
      </div>

      <div class="auth-visual-content">
        <div class="auth-visual-eyebrow">
          <i class="ri-team-line"></i>
          <span>Únete a nuestra familia</span>
        </div>
        <h2 class="auth-visual-title">Crea tu cuenta</h2>
        <p class="auth-visual-desc">
          Regístrate y disfruta de una experiencia sencilla para gestionar tus pedidos, cotizaciones y compras de
          pinturas y materiales de acabado.
        </p>
        <div class="auth-visual-feats">
          <div class="auth-feat"><i class="ri-file-list-3-line"></i> Gestiona tus pedidos</div>
          <div class="auth-feat"><i class="ri-draft-line"></i> Solicita cotizaciones</div>
          <div class="auth-feat"><i class="ri-shield-check-line"></i> Compra de forma segura</div>
        </div>
      </div>
    </div>

    <div class="auth-form-panel">
      <div class="auth-form-inner">
        <div class="auth-form-logo">
          <span class="brand-icon"><img :src="logoUrl" alt="Acabados y Diseños 1A" /></span>
          ACABADOS Y DISEÑOS <span class="text-primary">1A</span>
        </div>

        <div class="auth-form-top" v-if="paso === 1">
          <h1>Crear una cuenta</h1>
          <p>Completa tus datos para comenzar con nosotros</p>
        </div>
        <div class="auth-form-top" v-else>
          <h1>Verifica tu correo</h1>
          <p>Enviamos un código de 6 dígitos a {{ form.email }}. Ingrésalo para activar tu cuenta.</p>
        </div>

        <template v-if="paso === 1">
        <form @submit.prevent="registrar">
          <div class="form-group">
            <label class="form-label required">Nombre completo</label>
            <div class="input-group">
              <i class="ri-user-line input-icon"></i>
              <input v-model="form.nombreCompleto" type="text" class="form-control" placeholder="Ingresa tu nombre completo" required autocomplete="name" />
            </div>
          </div>

          <div class="auth-form-row">
            <div class="form-group">
              <label class="form-label required">Tipo de documento</label>
              <select v-model="form.tipoIdentificacion" class="form-control" required>
                <option value="CC">Cédula de ciudadanía</option>
                <option value="TI">Tarjeta de identidad</option>
                <option value="CE">Cédula de extranjería</option>
                <option value="NIT">NIT</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label required">Número de documento</label>
              <div class="input-group">
                <i class="ri-id-card-line input-icon"></i>
                <input
                  v-model="form.numeroIdentificacion"
                  type="text"
                  inputmode="numeric"
                  class="form-control"
                  :class="{ 'input-duplicado': cedulaDuplicada }"
                  required
                />
              </div>
              <p v-if="cedulaDuplicada" class="form-error-msg"><i class="ri-error-warning-fill"></i> Ya hay una cuenta registrada con este número de documento.</p>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label required">Correo electrónico</label>
            <div class="input-group">
              <i class="ri-mail-line input-icon"></i>
              <input v-model="form.email" type="email" class="form-control" :class="{ 'input-duplicado': emailDuplicado }" placeholder="correo@ejemplo.com" required autocomplete="email" />
            </div>
            <p v-if="emailDuplicado" class="form-error-msg"><i class="ri-error-warning-fill"></i> Este correo ya está registrado. ¿Ya tienes cuenta? <RouterLink to="/login">Inicia sesión</RouterLink>.</p>
          </div>

          <div class="auth-form-row">
            <div class="form-group">
              <label class="form-label required">Teléfono</label>
              <div class="input-group">
                <i class="ri-phone-line input-icon"></i>
                <input v-model="form.telefono" type="tel" class="form-control" :class="{ 'input-duplicado': telefonoDuplicado }" placeholder="300 000 0000" required autocomplete="tel" />
              </div>
              <p v-if="telefonoDuplicado" class="form-error-msg"><i class="ri-error-warning-fill"></i> Ya hay una cuenta registrada con este teléfono.</p>
            </div>
            <div class="form-group">
              <label class="form-label">Dirección</label>
              <div class="input-group">
                <i class="ri-map-pin-line input-icon"></i>
                <input v-model="form.direccion" type="text" class="form-control" placeholder="Ingresa tu dirección" autocomplete="street-address" />
              </div>
            </div>
          </div>

          <div class="auth-form-row">
            <div class="form-group">
              <label class="form-label required">Departamento</label>
              <select v-model="form.departamento" class="form-control" required>
                <option value="" disabled>Seleccionar...</option>
                <option v-for="depto in DEPARTAMENTOS" :key="depto" :value="depto">{{ depto }}</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label required">Ciudad / Municipio</label>
              <select v-model="form.ciudad" class="form-control" required :disabled="!form.departamento">
                <option value="" disabled>{{ form.departamento ? 'Seleccionar...' : 'Elige primero un departamento' }}</option>
                <option v-for="mun in municipiosDisponibles" :key="mun" :value="mun">{{ mun }}</option>
              </select>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label required">Contraseña</label>
            <div class="input-group password-wrapper">
              <i class="ri-lock-2-line input-icon"></i>
              <input
                ref="passwordEl"
                v-model="form.password"
                :type="mostrarPassword ? 'text' : 'password'"
                class="form-control"
                placeholder="••••••••"
                required
                autocomplete="new-password"
              />
              <i
                class="toggle-pass"
                :class="mostrarPassword ? 'ri-eye-line' : 'ri-eye-off-line'"
                @click="mostrarPassword = !mostrarPassword"
              ></i>
            </div>

            <div v-if="fuerzaPassword" class="pass-strength" :class="fuerzaPassword.nivel">
              <div class="pass-strength-bar"><span></span><span></span><span></span></div>
              <span class="pass-strength-label">{{ fuerzaPassword.label }}</span>
            </div>

            <div class="pass-requirements">
              <div class="pass-req-title">Requisitos de contraseña</div>
              <div class="pass-req-item" :class="{ ok: requisitos.longitud }"><i :class="requisitos.longitud ? 'ri-check-line' : 'ri-close-line'"></i> Mínimo 8 caracteres</div>
              <div class="pass-req-item" :class="{ ok: requisitos.mayuscula }"><i :class="requisitos.mayuscula ? 'ri-check-line' : 'ri-close-line'"></i> Al menos una mayúscula</div>
              <div class="pass-req-item" :class="{ ok: requisitos.numero }"><i :class="requisitos.numero ? 'ri-check-line' : 'ri-close-line'"></i> Al menos un número</div>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label required">Confirmar contraseña</label>
            <div class="input-group password-wrapper">
              <i class="ri-lock-2-line input-icon"></i>
              <input
                ref="confirmarPasswordEl"
                v-model="form.confirmarPassword"
                :type="mostrarConfirmarPassword ? 'text' : 'password'"
                class="form-control"
                placeholder="••••••••"
                required
                autocomplete="new-password"
              />
              <i
                class="toggle-pass"
                :class="mostrarConfirmarPassword ? 'ri-eye-line' : 'ri-eye-off-line'"
                @click="mostrarConfirmarPassword = !mostrarConfirmarPassword"
              ></i>
            </div>
          </div>

          <div class="auth-checkbox-row">
            <input id="acepta-terminos" ref="terminosCheckboxEl" v-model="form.aceptaTerminos" type="checkbox" />
            <label class="auth-checkbox-label" for="acepta-terminos">
              Acepto los <RouterLink to="/terminos-y-condiciones?from=registro">términos y condiciones</RouterLink>
              y la <RouterLink to="/politica-privacidad?from=registro">política de privacidad</RouterLink>.
            </label>
          </div>

          <p v-if="error" class="alert alert-danger"><i class="ri-error-warning-fill"></i> {{ error }}</p>

          <button type="submit" class="btn btn-primary btn-lg btn-block" :disabled="cargando">
            {{ cargando ? 'Creando cuenta...' : 'Crear cuenta' }} <i class="ri-arrow-right-line"></i>
          </button>
        </form>

        <p class="auth-prompt" style="margin-top:24px;">¿Ya tienes una cuenta? <RouterLink to="/login">Iniciar sesión</RouterLink></p>
        </template>

        <template v-else>
          <div class="code-inputs">
            <input
              v-for="(_, idx) in 6"
              :key="idx"
              :ref="(el) => (codigoInputs[idx] = el)"
              v-model="codigoDigitos[idx]"
              type="text"
              inputmode="numeric"
              maxlength="1"
              class="code-input"
              :class="{ filled: codigoDigitos[idx] }"
              @input="onCodigoInput(idx, $event)"
              @keydown="onCodigoKeydown(idx, $event)"
              @paste="idx === 0 && onCodigoPaste($event)"
            />
          </div>

          <p class="code-resend-info">
            ¿No recibiste el código?
            <button v-if="reenviarSegundos > 0" class="code-resend-btn" disabled>Reenviar en {{ reenviarSegundos }}s</button>
            <button v-else type="button" class="code-resend-btn" @click="reenviarCodigo">Reenviar código</button>
          </p>

          <p v-if="error" class="alert alert-danger"><i class="ri-error-warning-fill"></i> {{ error }}</p>

          <button type="button" class="btn btn-primary btn-lg btn-block" :disabled="cargando" @click="verificarCodigo">
            {{ cargando ? 'Verificando...' : 'Verificar cuenta' }} <i class="ri-arrow-right-line"></i>
          </button>
          <button type="button" class="back-link" style="display:flex;width:fit-content;margin:20px auto 0;" @click="paso = 1">
            <i class="ri-arrow-left-line"></i> Volver al formulario
          </button>
        </template>
      </div>
    </div>
  </div>
</template>
