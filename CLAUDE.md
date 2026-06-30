# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Apps is the application management service for the CyVerse Discovery Environment. It's a Clojure/Ring REST API that manages scientific workflow applications, tool metadata, job submissions, app categories, and HPC integration via Tapis/Agave.

## Code Formatting

- Follow the [clojure community style guidelines][1] when generating new code.
- Avoid repeated code when possible.
- Keep line lengths to 120 characters or fewer for readability.

## Common Development Commands

### Build and Run
- `lein uberjar` - Build the standalone JAR
- `lein run -- --config test.properties` - Start service with test config
- `./shell.sh` - Start test database (Docker) and drop into build container

### Code Quality
- `lein eastwood` - Run linter
- `lein cljfmt check` - Check code formatting
- `lein cljfmt fix` - Fix code formatting

### Testing
- `lein test` - Run unit tests only
- `RUN_INTEGRATION_TESTS=1 lein test` - Run all tests including integration tests (requires database)

## Architecture Overview

### Core Structure

The application follows a layered Clojure web service architecture:

1. **Entry Point** (`src/apps/core.clj`)
   - Main application initialization
   - Configuration loading from `.properties` file
   - Jetty server startup

2. **Routing Layer** (`src/apps/routes.clj` and `src/apps/routes/`)
   - Swagger API definition via Compojure-API
   - Thin route handlers delegating to service layer
   - Authentication middleware wrapping

3. **Service Layer** (`src/apps/service/`)
   - Business logic organized by domain
   - Implements the multi-system app client pattern via protocols

4. **Persistence Layer** (`src/apps/persistence/`)
   - Database access using a mix of Korma (legacy) and HoneySQL (modern)
   - Transactions via `apps.util.db/transaction`

5. **Client Layer** (`src/apps/clients/`)
   - HTTP clients for external microservices (jex, data-info, metadata, permissions, notifications, iplant-groups)

### Multi-System App Client Pattern
- **Protocol-based abstraction** (`apps.protocols/Apps`): Defines all app operations as protocol methods.
- **Three implementations**:
  - `DeApps` - Native DE apps (most features)
  - `TapisApps` - HPC apps via Tapis/Agave integration
  - `CombinedApps` - Aggregates both, using futures for parallel queries across systems.
- **Client resolution**: `apps.service.apps-client/get-apps-client` builds the appropriate client(s) based on config and user context.
- Apps are identified by `system-id` (e.g., "de", "agave") + `app-id`.

### Key Dependencies
- **Web Framework**: Compojure-API + Ring
- **Database**: PostgreSQL via Kameleon (Korma legacy, HoneySQL modern)
- **HTTP Client**: clj-http
- **JSON**: Cheshire
- **HPC Integration**: Mescal (Tapis/Agave client)

### Configuration
The application expects a `.properties` config file, loaded in `core.clj` startup:
- Test config: `test.properties` assumes local services on standard ports
- Access via `apps.util.config` functions (e.g., `(config/db-host)`)

### Namespace Organization
- `apps.service.*` - Business logic, protocol implementations
- `apps.persistence.*` - Database queries (Korma/HoneySQL)
- `apps.routes.*` - HTTP endpoint handlers
- `apps.clients.*` - External service clients
- `apps.metadata.*` - App metadata operations
- `apps.util.*` - Utilities (config, conversions, db helpers)

### Error Handling
- Use Slingshot `throw+` with error maps: `{:type :clojure-commons.exception/not-found :error "..."}`
- Common error types: `:not-found`, `:not-authorized`, `:bad-request`, `:temporary-redirect`
- Service helpers in `apps.util.service`: `not-found`, `bad-request`, etc.

### User Context
- The `apps.user/current-user` dynamic var holds the authenticated user map.
- User format: `{:username "user@iplantcollaborative.org" :shortUsername "user" ...}`
- Use `apps.user/with-user` macro for testing with a mock user.

### Job Types
The system handles multiple job types with different submission paths: `de` (native), `interactive` (VICE), `osg` (Open Science Grid), and `agave` (Tapis HPC).

[1]: https://guide.clojure.style/
