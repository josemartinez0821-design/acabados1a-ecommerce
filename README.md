# Acabados y Diseños 1A — Web

Aplicación web de comercio electrónico y gestión para **Acabados y Diseños 1A**, empresa familiar de Tesalia, Huila, dedicada a la venta de pinturas, materiales de acabado y servicios de instalación (Drywall, PVC, estuco, Graniplast).

Proyecto formativo SENA (Tecnólogo en Análisis y Desarrollo de Software).

## Estructura

```
frontend/   Vue 3 + Vite + Vue Router + Pinia
backend/    Spring Boot 4 (Java 21) + MariaDB
```

## Stack

**Frontend**: Vue 3 (Composition API, `<script setup>`), Vite, Vue Router, Pinia, Axios.

**Backend**: Spring Boot, Spring Security + JWT, Spring Data JPA (Hibernate), MariaDB, envío de correo vía Gmail SMTP.

## Funcionalidades

- Catálogo de productos con filtros, búsqueda, variantes de tamaño/color
- Carrito de compras y checkout
- Cotizaciones (productos y servicios)
- Ventas: checkout real, anticipos de cotización, gestión desde el panel admin
- PQRS: radicación y gestión con respuesta del equipo
- Inventario: umbrales de stock y movimientos manuales (entrada/salida/ajuste/devolución)
- Autenticación con verificación de correo y recuperación de contraseña (JWT, roles Administrador/Cliente)
- Panel de administración: dashboard, productos, servicios, inventario, ventas, cotizaciones, PQRS, reportes

La relación de cada requisito funcional con su código y sus casos de prueba está en
[`docs/MATRIZ_TRAZABILIDAD.md`](docs/MATRIZ_TRAZABILIDAD.md).

## Cómo correr el proyecto

### Backend

```bash
cd backend
cp src/main/resources/application.properties.example src/main/resources/application.properties
# Editar application.properties con los datos reales de tu base de datos MariaDB y credenciales SMTP
./mvnw spring-boot:run
```

Requiere una base de datos MariaDB local con el esquema del proyecto ya creado.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Por defecto apunta a `http://localhost:8080/api` (ver `frontend/.env`).

### Despliegue, versiones y rollback

El procedimiento completo (requisitos, configuración por variables de entorno, arranque, pruebas de
humo y plan de rollback) está en [`docs/MANUAL_DESPLIEGUE.md`](docs/MANUAL_DESPLIEGUE.md). Las
versiones desplegadas se marcan con etiquetas de versionamiento semántico (`git tag -l -n1`); los
secretos van en variables de entorno (`backend/.env.example`), nunca en el repositorio.

## Flujo de trabajo con ramas

Desde la auditoría cruzada (24/09/2026), ningún cambio va directo a `main`. Cada cambio se hace en
una rama corta que sale de `main` y vuelve con un merge sin fast-forward (`--no-ff`), para que el
historial muestre qué commits pertenecen a cada cambio.

| Prefijo | Para qué | Ejemplo |
|---|---|---|
| `fix/` | Corregir un defecto o un hallazgo | `fix/inventario-dto` |
| `refactor/` | Cambio interno que no altera el comportamiento | `refactor/venta-service-crear` |
| `feature/` | Funcionalidad nueva | `feature/pasarela-wompi` |
| `docs/` | Documentación | `docs/matriz-trazabilidad` |

```bash
git checkout main
git pull
git checkout -b fix/nombre-corto
# ...commits del cambio...
git checkout main
git merge --no-ff fix/nombre-corto
git push origin main fix/nombre-corto
```

Las ramas se dejan publicadas en GitHub (pestaña *Branches*) como evidencia del cambio. Si el cambio
necesita revisión del otro integrante antes de integrarse, se abre un Pull Request en GitHub en lugar
del merge local. Para ver el historial con sus ramas: `git log --oneline --graph`.
