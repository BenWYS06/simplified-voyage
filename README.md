# Ben Airline — Airline GDS Microservices Platform
[![user-service](https://github.com/BenWYS06/simplified-voyage/actions/workflows/user-service.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/user-service.yml)
[![airline-core-service](https://github.com/BenWYS06/simplified-voyage/actions/workflows/airline-core-service.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/airline-core-service.yml)
[![flight-ops-service](https://github.com/BenWYS06/simplified-voyage/actions/workflows/flight-ops-service.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/flight-ops-service.yml)
[![seat-service](https://github.com/BenWYS06/simplified-voyage/actions/workflows/seat-service.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/seat-service.yml)
[![booking-service](https://github.com/BenWYS06/simplified-voyage/actions/workflows/booking-service.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/booking-service.yml)
[![payment-service](https://github.com/BenWYS06/simplified-voyage/actions/workflows/payment-service.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/payment-service.yml)
[![notification-service](https://github.com/BenWYS06/simplified-voyage/actions/workflows/notification-service.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/notification-service.yml)
[![api-gateway](https://github.com/BenWYS06/simplified-voyage/actions/workflows/api-gateway.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/api-gateway.yml)
[![backend-shared](https://github.com/BenWYS06/simplified-voyage/actions/workflows/backend-shared.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/backend-shared.yml)
[![secret-scan](https://github.com/BenWYS06/simplified-voyage/actions/workflows/secret-scan.yml/badge.svg)](https://github.com/BenWYS06/simplified-voyage/actions/workflows/secret-scan.yml)

A production-grade **Airline Global Distribution System (GDS)** built with Spring Boot microservices, event-driven architecture, and cloud-native patterns, plus a React (Vite) web frontend. The platform covers the complete airline booking lifecycle — from flight search and seat selection through payment processing and passenger notifications.

> **Built with Claude Code.** This project is developed with an AI-assisted workflow: designs are agreed section by section, every step is approved before code is written, and every change is verified before it is committed. See [docs/AI_WORKFLOW.md](docs/AI_WORKFLOW.md) for the loop, and [docs/booking-refactor/](docs/booking-refactor/) for a full worked example (booking saga: idempotency, row locks, deadlock avoidance, transactional outbox).

---

## Table of Contents

1. [Repository Structure](#repository-structure)
2. [Features](#features)
3. [Architecture Overview](#architecture-overview)
4. [Services](#services)
5. [Data Flow](#data-flow)
6. [Technology Stack](#technology-stack)
7. [Design Patterns](#design-patterns)
8. [Security](#security)
9. [API Reference](#api-reference)
10. [Frontend](#frontend)
11. [Running the Project](#running-the-project)
12. [Configuration](#configuration)
13. [Known Limitations](#known-limitations)

---

## Repository Structure

```
.
├── backend/                             # Spring Boot microservices (Maven multi-module)
│   ├── pom.xml                          # Root parent POM — version management
│   ├── common-lib/                      # Shared DTOs, enums, events, exceptions
│   │   └── src/main/java/com/ben/common_lib/
│   │       ├── dto/                     # Shared data transfer objects
│   │       ├── embeddable/              # JPA embeddables (ContactInfo, Address, GeoCode)
│   │       ├── enums/                   # Shared enums (BookingStatus, CabinClassType, ...)
│   │       ├── event/                   # Kafka event objects (BookingConfirmedEvent, ...)
│   │       ├── exception/               # Custom exceptions
│   │       └── payload/
│   │           ├── request/             # Inbound request DTOs
│   │           └── response/            # Outbound response DTOs
│   ├── cloud/                           # Infrastructure services
│   │   ├── api-gateway/                 # Gateway MVC + OAuth2 resource server
│   │   ├── config-server/               # Spring Cloud Config Server
│   │   └── service-registry/            # Eureka Server
│   └── services/                        # Business microservices
│       ├── user-service/
│       ├── airline-core-service/
│       ├── location-service/
│       ├── flight-ops-service/
│       ├── seat-service/
│       ├── pricing-service/
│       ├── ancillary-service/
│       ├── booking-service/
│       ├── payment-service/
│       └── notification-service/
│
├── frontend/                            # React 19 + Vite web app
│   └── src/
│       ├── pages/                       # auth, Landing, Onboarding, traveler, airline, super-admin
│       ├── components/                  # Shared + feature components (aircraft, cabinClass, ui, ...)
│       ├── Redux/                       # Redux Toolkit slices & thunks (store: globleState.js)
│       ├── services/, utils/api.js      # API clients (axios, base URL http://localhost:5000)
│       └── ...                          # hooks, contexts, lib, constants, styles
│
├── config-repo/                         # Spring Cloud Config files (one .yml per service)
├── docker/                              # Docker Compose stack, Keycloak realm, DB init scripts
│   ├── docker-compose.yml               # Full stack deployment
│   ├── docker-compose.dev.yml           # Development stack
│   ├── init-databases.sql               # Creates one MySQL database per service
│   └── keycloak/realm-export.json       # ben-airline realm, clients and roles
└── README.md
```

---

## Features

### For Passengers (Customer-Facing)
- **Flight Search** — Multi-criteria search with filters: route, date, cabin class, airline, alliance, price range, departure/arrival time window, max duration, and sorting (price / departure / duration)
- **Seat Selection** — Interactive cabin-class seat map with availability status (window, aisle, middle, extra legroom)
- **Ancillary Services** — Add meals, insurance, and other add-ons at booking
- **Booking Management** — View, modify, and cancel bookings; track status in real time
- **Payment Processing** — Razorpay and Stripe integration with secure payment verification
- **Booking Confirmation Email** — Production-grade HTML email with flight details, passenger list, ticket numbers, fare breakdown, baggage allowance, fare benefits, and payment receipt
- **SMS Notification** — Instant booking confirmation via Twilio
- **Ticket Management** — E-ticket generation with unique ticket numbers per passenger
- **Booking History** — Full history of past and upcoming flights

### For Airline Administrators
- **Airline Management** — Register, approve, suspend, and ban airlines; manage status lifecycle
- **Aircraft Management** — Define aircraft types with seat capacities
- **Flight Management** — Create and manage master flights and recurring flight schedules
- **Flight Instance Management** — Manage specific departures with terminal, gate, and real-time status
- **Pricing & Fares** — Define fares per cabin class with full benefit matrix (refund, date change, lounge, meals, priority boarding)
- **Baggage Policies** — Cabin and check-in baggage rules linked to fares
- **Seat Maps** — Create seat map templates and assign per aircraft/cabin
- **Meal Management** — Create meal offerings and assign to specific flights
- **Insurance Products** — Define insurance coverage options
- **Ancillary Catalogue** — Manage ancillary service catalogue per airline
- **Booking Analytics** — Daily and monthly booking statistics with revenue tracking

### For System Administrators
- **Multi-Airline Support** — Isolated data per airline; RBAC protects cross-airline access
- **City & Airport Data** — Manage global location data with IATA codes, timezones, and geolocation
- **Centralized Configuration** — All service config driven from a Git-backed Config Server
- **Service Discovery** — Eureka-based dynamic service registration
- **Fault Tolerance** — Circuit breakers protect all inter-service calls with configurable thresholds

---

## Architecture Overview

```
                                    ┌─────────────────────────────────────────────────────┐
                                    │        CLIENT (React frontend :5173 / Mobile)       │
                                    └───────────────────────────┬─────────────────────────┘
                                                                │ HTTPS :5000
                                    ┌───────────────────────────▼─────────────────────────┐
                                    │                    API GATEWAY                      │
                                    │       OAuth2 Auth · Routing · Circuit Breaker       │
                                    └──┬───┬───┬───┬───┬───┬───┬───┬───┬───┬───┬────────┘
                                       │   │   │   │   │   │   │   │   │   │   │
          ┌────────────────────────────┘   │   │   │   │   │   │   │   │   │   └──────────────────┐
          │         ┌──────────────────────┘   │   │   │   │   │   │   │   └─────────────┐        │
          ▼         ▼          ▼               ▼   ▼   ▼   ▼   ▼   ▼   ▼                ▼        ▼
   ┌────────────┐ ┌──────────┐ ┌────────────┐ ...                           ┌───────────┐ ┌──────────────┐
   │    User    │ │ Airline  │ │ Flight-Ops │                               │  Booking  │ │   Payment    │
   │  Service   │ │  Core   │ │  Service  │                               │  Service  │ │   Service    │
   └────────────┘ └──────────┘ └─────┬──────┘                               └─────┬─────┘ └──────┬───────┘
                                     │ Feign                                       │ Feign         │
                                     ├──► Pricing Service                          │               │
                                     ├──► Seat Service                             │               │
                                     └──► Airline Core                             │               │
                                                                                   ▼               ▼
                                    ┌──────────────────────────────────────────────────────────────────────┐
                                    │                         APACHE KAFKA                                  │
                                    │   booking.confirmed  │  payment.completed  │  payment.failed          │
                                    └──────┬───────────────────────────────────────────────────────────────┘
                                           │
                                    ┌──────▼──────────────────────────────────────────────────────────────┐
                                    │   Seat Service (mark seats BOOKED)  │  Notification Service (email/SMS) │
                                    └─────────────────────────────────────────────────────────────────────┘

                     ┌─────────────────────────────────────────────────────────────────────────────────┐
                     │                         INFRASTRUCTURE                                          │
                     │    Eureka (Service Registry) ·  Config Server (Git-backed) · MySQL per service  │
                     └─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Services

### Infrastructure Services

| Service | Port | Role |
|---|---|---|
| **API Gateway** | 5000 (external) | OAuth2 resource server, trusted user context, routing, CORS, circuit breaker fallbacks |
| **Keycloak** | 8180 (external) | OpenID Connect identity provider, login, sessions, and realm/client roles |
| **Config Server** | 8888 | Centralized config pulled from Git (`config-repo/`) for all services |
| **Service Registry** | 8761 | Eureka — service registration and discovery |
| **Kafka** | 9092 | Event streaming (KRaft mode — no Zookeeper) |
| **MySQL** | varies | Dedicated database per business service |

### Business Services

| Service | Port | Database | Kafka | Responsibility |
|---|---|---|---|---|
| **user-service** | dynamic | `airline_user` | — | Local application profiles and Keycloak-subject-to-numeric-ID mapping |
| **airline-core-service** | dynamic | `airline_core_db` | — | Airline + aircraft management, alliance lookup |
| **location-service** | dynamic | `airline_location_db` | — | Cities, airports, IATA codes, geolocation |
| **flight-ops-service** | dynamic | `airline_flight_db` | Producer | Flights, schedules, instances, advanced search |
| **seat-service** | dynamic | `airline_seat_db` | Consumer + Producer | Seat maps, cabin config, availability, pricing |
| **pricing-service** | dynamic | `airline_pricing_db` | — | Fares, baggage policies, fare rules |
| **ancillary-service** | dynamic | `airline_ancillary_db` | — | Meals, insurance, ancillary catalogue |
| **booking-service** | dynamic | `airline_booking_db` | Consumer | Bookings, passengers, tickets, statistics |
| **payment-service** | dynamic | `airline_payment_db` | Producer | Razorpay/Stripe integration, verification, refunds |
| **notification-service** | 8094 | — | Consumer | Email (Gmail SMTP / Thymeleaf) + SMS (Twilio) |

---

## Data Flow

### Flight Search

```
Client → API Gateway → flight-ops-service
  │
  ├─ 1. Resolve airline filter
  │       └─[Feign]→ airline-core-service  (by IATA code or alliance name)
  │
  ├─ 2. Query DB via JPA Specification (Criteria API)
  │       Filters: airports · departure date · passenger count
  │                cabin class · duration · departure/arrival time window
  │       (No price JOIN — kept in pricing-service for clean separation)
  │
  ├─ 3. Batch-fetch lowest fares for result page
  │       └─[Feign POST]→ pricing-service  /api/fares/search?cabinClass=ECONOMY
  │                        Body: [flightId1, flightId2, ...]
  │                        Returns: Map<flightId, cheapestFare>
  │
  ├─ 4. Apply price range filter (post-fetch)
  │       Remove flights outside min/max price
  │
  └─ 5. Return Page<FlightInstanceResponse> with enriched fare data
```

### Booking & Payment

```
Client → API Gateway → booking-service
  │
  ├─ 1.  Validate flight      [Feign] → flight-ops-service
  ├─ 2.  Validate seats        [Feign] → seat-service
  ├─ 3.  Validate ancillaries  [Feign] → ancillary-service
  ├─ 4.  Fetch fare            [Feign] → pricing-service
  ├─ 5.  Create Booking (status: PENDING)
  ├─ 6.  Initiate payment      [Feign] → payment-service → Razorpay/Stripe
  └─ 7.  Return payment URL to client
         │
         │  [Client completes payment on gateway]
         │
  ┌──────▼──────────────────────────────────────────────────────────┐
  │  payment-service receives callback from Razorpay / Stripe       │
  │  → Verify signature                                             │
  │  → Publish PaymentCompletedEvent ─────────────► Kafka           │
  └──────────────────────────────────────────────────────────────────┘
         │
         ▼  [Kafka: payment.completed]
  ┌──────────────────────────────────────────────────────────────────┐
  │  booking-service (PaymentEventListener)                         │
  │  → Update Booking status to CONFIRMED                           │
  │  → Fetch FlightInstance, Fare, User details [Feign]             │
  │  → Publish BookingConfirmedEvent ──────────────► Kafka          │
  └──────────────────────────────────────────────────────────────────┘
         │
         ▼  [Kafka: booking.confirmed]  (two parallel consumers)
  ┌──────────────────┐       ┌──────────────────────────────────────┐
  │   seat-service   │       │        notification-service          │
  │  Mark seats as   │       │  Send confirmation email (HTML)      │
  │  BOOKED          │       │  Send SMS via Twilio                 │
  └──────────────────┘       └──────────────────────────────────────┘
```

### Booking Confirmation Email Content

The HTML email sent after a confirmed booking includes:

- **Header** — Airline logo, "Booking Confirmed ✓" badge, personalised greeting
- **Booking Reference** — Large, prominent, monospaced reference number
- **Flight Route Card** — Departure/arrival IATA codes, city names, times, date, flight number, duration, terminal, gate, cabin class, aircraft model
- **Passenger Table** — Per passenger: name, adult/child type, ticket number, passport number, nationality, and any special requirements (wheelchair, dietary)
- **Baggage Allowance** — Cabin and check-in allowance from the fare's baggage policy
- **Fare Benefits** — Priority boarding, lounge access, complimentary meals, date change, refund entitlement — shown as ✓ / ✗
- **Payment Summary** — Base fare (× passengers), taxes, seat fees, ancillaries, meals, **total paid**, transaction ID, provider reference, payment gateway, payment date
- **Web Check-In CTA** — Button linking to the check-in portal
- **Important Information** — Check-in open window, airport deadline, ID requirements, refund/change policy
- **Footer** — Support email, phone, legal notice

---

## Technology Stack

### Backend Framework

| Technology | Version | Purpose |
|---|---|---|
| Spring Boot | 4.0.2 | Base application framework |
| Spring Cloud | 2025.1.0 | Microservices toolkit (Config, Eureka, Gateway, Feign) |
| Spring Data JPA | managed | ORM with Criteria API support |
| Spring Security | managed | Authentication and RBAC |
| Spring Kafka | managed | Kafka producer/consumer integration |
| Spring Mail | managed | JavaMailSender (email sending) |
| Thymeleaf | managed | HTML email template rendering |

### Frontend

| Technology | Version | Purpose |
|---|---|---|
| React | 19 | UI library |
| Vite | 7 | Dev server and bundler (`@` alias → `src/`) |
| React Router | 7 | Client-side routing |
| Redux Toolkit | 2 | Global state (slices + async thunks) |
| Tailwind CSS + shadcn/ui (Radix UI) | 4 | Styling and accessible UI primitives |
| Formik + Yup | — | Form state and validation |
| Axios | 1 | HTTP client to the API Gateway |
| Framer Motion, date-fns, @react-pdf/renderer, html2canvas | — | Animation, dates, PDF e-tickets |

### Infrastructure

| Technology | Version | Purpose |
|---|---|---|
| Apache Kafka | 4.1.1 | Async event streaming (KRaft — no Zookeeper) |
| MySQL | 8.0 | Primary relational database (one DB per service) |
| Eureka Server | Spring Cloud | Service discovery and registration |
| Spring Cloud Config | Spring Cloud | Git-backed centralised configuration |
| Spring Cloud Gateway | Spring Cloud | API gateway with WebMVC routing |

### Resilience

| Technology | Purpose |
|---|---|
| Resilience4j | Circuit breaker, retry, rate limiter |
| Spring Cloud Circuit Breaker | Integration layer for Resilience4j |
| OpenFeign | Declarative HTTP client with fallback support |

### Security

| Technology | Purpose |
|---|---|
| Keycloak | OpenID Connect provider, user login, sessions, and role management |
| Spring Security OAuth2 Resource Server | Keycloak access-token validation and URL authorization |

### Payment Gateways

| Gateway | Purpose |
|---|---|
| Razorpay | Primary payment gateway (India) |
| Stripe | Alternative payment gateway (global) |

### Notifications

| Technology | Purpose |
|---|---|
| Gmail SMTP (587/TLS) | Email delivery |
| Twilio | SMS delivery |
| Thymeleaf (HTML) | Responsive HTML email templates with inline CSS |

### Build & Deployment

| Tool | Purpose |
|---|---|
| Maven (multi-module) | Build tool with parent POM for version management |
| Google Jib | Build Docker images via Maven without a Dockerfile |
| Docker Compose | Local and production container orchestration |
| Spring Boot DevTools | Live reload during development |
| Lombok | Compile-time boilerplate generation |
| npm / Vite | Frontend build (`npm run build` → `frontend/dist`) |

---

## Design Patterns

### Microservices Patterns

| Pattern | Where Used |
|---|---|
| **API Gateway** | Single entry point — all traffic routed through `api-gateway` |
| **Service Registry** | Eureka — services register themselves; gateway resolves URLs dynamically |
| **Circuit Breaker** | All Feign clients protected with Resilience4j; custom thresholds per client |
| **Fallback** | Every Feign client has a `*ClientFallback.java` returning safe default values |
| **Config Server** | All service configs in a Git repo; services pull on startup |
| **Saga (choreography)** | Booking → Payment → Seat Reservation → Notification via Kafka events |

### Data Patterns

| Pattern | Where Used |
|---|---|
| **Repository Pattern** | `JpaRepository` for all CRUD; `JpaSpecificationExecutor` for dynamic queries |
| **Specification Pattern** | `FlightInstanceSpecification` — Criteria API for complex flight search filters |
| **DTO / Mapper** | Request/Response DTOs in `common-lib`; Mapper classes per service |
| **Denormalization** | `Fare.cabinClass` stored on Fare (avoids join with seat-service during search) |
| **Bulk Endpoints** | `POST /bulk` on all resource controllers — reduces network round-trips |
| **Per-Request Cache** | `HashMap` caches inside a single request — avoids N+1 in enrichment loops |

### Event-Driven Patterns

| Pattern | Where Used |
|---|---|
| **Event Sourcing** | Kafka topics carry state-change events (`BookingConfirmedEvent`, `PaymentCompletedEvent`) |
| **Pub/Sub** | `booking.confirmed` consumed by both `seat-service` and `notification-service` |
| **Event Enrichment** | `BookingEventProducer` enriches event with user, flight, fare, baggage data before publishing |
| **Consumer Groups** | Each service has its own group ID — each gets its own copy of every message |

### Cross-Cutting Concerns

| Concern | Approach |
|---|---|
| Authentication | Keycloak token validated at gateway; trusted local userId/email/roles forwarded internally |
| Authorisation | Role checked at gateway (URL-level) and service (method-level) |
| Error handling | `ResourceNotFoundException`, `PaymentException`, `UserException` in common-lib |
| Observability | `/actuator/health`, `/actuator/circuitbreakers` on all services |
| Logging | Slf4j + `@Slf4j` (Lombok) throughout |

---

## Security

### Roles

| Role | Permissions |
|---|---|
| `ROLE_SYSTEM_ADMIN` | Create/manage airlines, airports, cities; approve/suspend/ban airlines |
| `ROLE_AIRLINE_OWNER` | Manage own airline's flights, schedules, fares, seats, ancillaries |
| `ROLE_CUSTOMER` | Search flights, make bookings, view profile, manage own bookings |

### Keycloak / OAuth2 Authentication Flow

```
1. The frontend signs in with Keycloak using Authorization Code + PKCE.
        │
        ▼
   Keycloak returns an access token containing subject, email, and roles.
        │
        ▼
   The frontend sends: Authorization: Bearer <keycloak-access-token>
        │
        ▼
   API Gateway validates issuer, signature, timestamps, and roles through
   Spring Security's servlet OAuth2 Resource Server support.
        │
        ▼
   Gateway resolves the Keycloak subject to the local numeric user ID,
   strips client-supplied X-User-* headers and the bearer token, then sets:
     X-User-Id, X-User-Email, X-User-Roles
        │
        ▼
   Internal business services consume the trusted headers.
```

### Endpoint Protection

| Path | Access |
|---|---|
| Keycloak authorization/token endpoints | Public identity-provider endpoints |
| `/actuator/health`, `/actuator/info`, `/fallback` | Public |
| All gateway `/api/**` routes | Valid Keycloak access token |
| `POST /api/airlines`, `POST /api/airports`, `POST /api/cities` | ROLE_SYSTEM_ADMIN |
| `GET /api/flights/search`, all GET endpoints | Any authenticated user |
| `POST /api/bookings`, `GET /api/bookings/user/**` | ROLE_CUSTOMER |
| `GET /api/bookings/airline/**` | ROLE_AIRLINE_OWNER / ROLE_SYSTEM_ADMIN |

---

## API Reference

### Authentication

Authentication and registration are handled by Keycloak, not by an application REST
endpoint. The frontend uses the `airline-frontend` public client with Authorization Code
+ PKCE, then sends the resulting access token as `Authorization: Bearer <token>`.

### Flight Search — `GET /api/flights/search`
```
?departureAirportId=1
&arrivalAirportId=2
&departureDate=2026-03-15
&passengers=2
&cabinClass=ECONOMY
&airlines=AI,UK             (optional)
&alliance=star              (optional)
&minPrice=1000              (optional)
&maxPrice=10000             (optional)
&departureTimeRange=MORNING (optional: MORNING/AFTERNOON/EVENING/NIGHT)
&maxDuration=180            (optional, minutes)
&sortBy=price               (optional: departure/arrival/duration/price)
&sortOrder=ASC
&page=0&size=20
```

### Create Booking — `POST /api/bookings`
```json
{
  "flightInstanceId": 101,
  "flightId": 10,
  "fareId": 5,
  "cabinClass": "ECONOMY",
  "tripType": "ONE_WAY",
  "ancillaryIds": [1, 2],
  "mealIds": [3],
  "contactInfo": {
    "email": "john@example.com",
    "phone": "+919876543210"
  },
  "passengers": [
    {
      "firstName": "John",
      "lastName": "Smith",
      "email": "john@example.com",
      "phone": "+919876543210",
      "dateOfBirth": "1990-05-15",
      "gender": "MALE",
      "seatInstanceId": 201,
      "passportNumber": "AB1234567",
      "nationality": "Indian"
    }
  ]
}
```
**Returns:** `PaymentInitiateResponse` with `checkoutUrl` for Razorpay/Stripe

### Verify Payment — `POST /api/payments/verify`
```json
{
  "razorpayPaymentId": "pay_xyz",
  "razorpayOrderId": "order_abc",
  "razorpaySignature": "sig_123"
}
```
**On success:** triggers the event chain → booking confirmed → seats reserved → email + SMS sent

### Common Response Shapes

**Paginated list:**
```json
{
  "content": [ ... ],
  "totalElements": 120,
  "totalPages": 6,
  "pageNumber": 0,
  "pageSize": 20
}
```

**Error:**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Booking not found with ID: 99"
}
```

**Circuit breaker open:**
```json
{
  "status": 503,
  "message": "Service temporarily unavailable. Please retry."
}
```

---

## Frontend

React 19 + Vite single-page app in `frontend/`. All API calls go through the axios
instance in `src/utils/api.js`, which targets the API Gateway at `http://localhost:5000`.
Global state lives in Redux Toolkit (`src/Redux/globleState.js`); each domain has its own
slice + thunk folder (e.g. `Redux/ancillary/`). Forms use Formik + Yup, UI is built from
Tailwind CSS and shadcn/ui components.

### Application Areas & Routes

The dev server runs on `http://localhost:5173`.

| Area | Base path | Main routes |
|---|---|---|
| Public / Auth | `/` | `/`, `/login`, `/register`, `/forgot-password`, `/reset-password/:token` |
| Airline onboarding | `/airline-onboarding` | Multi-step registration wizard for new airlines |
| Traveler | `/` | `/search`, `/search-results`, `/booking-review`, `/payment`, `/booking-success/:bookingId`, `/bookings`, `/view-ticket/:bookingId`, `/ticket/:pnr`, `/profile` |
| Airline dashboard | `/airline/*` | `aircraft`, `flights`, `instances`, `fare`, `fare-rules`, `baggage-policies`, `meals`, `ancillaries`, `insurance-coverages`, `bookings`, `bookings/statistics`, `profile` |
| Super admin dashboard | `/super-admin/*` | `airlines` (+ `pending`, `suspended`, `compliance`, `commission`), `airports`, `cities`, `analytics`, `airline-performance`, `airport-performance`, `reports` |

Route definitions: `src/App.jsx`, `src/pages/airline/routes/AirlineRoutes.jsx`,
`src/pages/super-admin/routes/SuperAdminRoutes.jsx`. Sidebar entries for the airline
dashboard are in `src/pages/airline/Sidebar/sideBarSections.js`.

### Modules

**Airline Onboarding Wizard** (`pages/Onboarding/`) — `AirlineOnboardingWizard.jsx` with a
`Stepper` and four steps: *Owner Details* → *Airline Details* (name, IATA/ICAO codes, country,
logo, website) → *Support & Contact* → *Review & Confirmation*, ending on a `SuccessScreen`.
Each step is validated with Yup; progress is saved to `localStorage` so the user can
resume after a page refresh.

**Airline Profile & Management** (`pages/airline/Dashboard/`) — `AirlineProfile`,
`AirlineUpdateForm` and `AirlineManagement` map directly to the `AirlineRequest` /
`AirlineResponse` DTOs. Validation: IATA code `^[A-Z]{2}$`, ICAO code `^[A-Z]{3}$`,
name 2–100 characters, optional website/logo URL (`http(s)://`, logo must be an image),
optional support email and phone. Status badges follow the backend `AirlineStatus` enum
(`ACTIVE`, `INACTIVE`, `BANNED`).

**Flight Management** (`pages/airline/Dashboard/FlightManagment/`) — `FlightManagement.jsx`
is the list container (search, status filters, stats cards) and renders one `FlightCard`
per flight; it is the element for the `/airline/flights` route.

**Aircraft, Cabins & Seat Maps** (`components/aircraft/`, `components/cabinClass/`)
- `AircraftTable` — fleet table with search, status filter, sorting and pagination
  (`/airline/aircraft?page=1&size=25&search=boeing&status=ACTIVE&sortBy=model&sortDirection=asc`)
- `AircraftHeader` — aircraft detail header (model, registration, capacity, status)
- `CabinCard` — per-cabin summary (class, seat count, layout, amenities)
- `SeatMapGrid` — interactive seat grid (window / aisle / middle / extra legroom, availability)
- `SeatConfigDrawer` — side drawer to edit a single seat's type and attributes
- `CabinClassForm` — one form for create and edit of a cabin class (basic info, seat
  configuration, status & availability, premium amenities), used by `CabinClassCreate`
  and `CabinClassEdit`

Routes: `/airline/aircraft`, `/airline/aircraft/new`, `/airline/aircraft/:aircraftId`,
`/airline/aircraft/:aircraftId/edit`, `/airline/aircraft/:aircraftId/cabin/new`,
`/airline/aircraft/:aircraftId/cabin/:cabinId/edit`,
`/airline/aircraft/:aircraftId/cabin/:cabinId/seat-map/create`,
`/airline/aircraft/:aircraftId/cabin/:cabinId/seat-map/:seatMapId[/edit]`.

**Airport Management** (`pages/super-admin/airport/`) — `AirportManagementNew.jsx` with
statistics cards (total airports, countries, timezones, active), debounced search and
filters, pagination (10/25/50/100), bulk selection, a Formik + Yup form modal and a delete
confirmation modal. Form fields: `iataCode` (3 characters), `name`, `detailedName`,
`timeZone`, `cityId`, optional `address` (street, postal code, city, country, region) and
optional `geoCode` (latitude −90..90, longitude −180..180).

**Booking Review** (`pages/traveler/BookingReview/`) — MakeMyTrip-style checkout page:
flight summary → traveller details (one form per passenger, generated from the passenger
count) → seat selection → meal selection → flexibility add-on → trip insurance
(`TripSecure`) → cancellation & date-change policy → important information, with a sticky
`FareSummaryCard` (desktop sidebar / mobile bottom bar) that recalculates the total live.
All traveller forms are validated before the user can continue to payment.

### Ancillary Configuration

| Step | UI | Route |
|---|---|---|
| Master catalogue | `pages/airline/Dashboard/Ancillaries/` (`AncillaryList`, `AncillaryForm`, `AncillaryCard`) — grid with category/level filters, RFISC code | `/airline/ancillaries`, `/airline/ancillaries/create`, `/airline/ancillaries/edit/:id` |
| Cabin-specific pricing | `pages/airline/Dashboard/FlightCabinAncillaries/FlightCabinAncillaryForm.jsx` — price per cabin, "Included in Fare" toggle, availability, max quantity | `/airline/cabin-ancillaries/new` |
| Meals / insurance | Meal assignment per flight, insurance coverage management | `/airline/flights/:flightId/meals/assign`, `/airline/insurance-coverages` |
| Traveller checkout | Add-on sections of the Booking Review page | `/booking-review` |

Typical workflow:
1. Create catalogue items once — e.g. *Extra Checked Bag 23kg* (BAGGAGE, FLIGHT level, RFISC `0CC`),
   *In-flight WiFi* (ONBOARD_SERVICE, CABIN level, RFISC `0G3`), *Hot Meal* (MEAL, CABIN level).
2. Set cabin prices — e.g. WiFi and meal included in Business, WiFi $10 / meal $15 in Economy.
3. Travellers see the categorised add-ons with the right price during booking.

Troubleshooting: a missing sidebar item → check `pages/airline/Sidebar/sideBarSections.js`;
a route that does not load → check `pages/airline/routes/AirlineRoutes.jsx`; undefined Redux
state → make sure the reducer is registered in `Redux/globleState.js`.

---

## Running the Project

### Prerequisites
- Java 17+
- Maven 3.9+
- Node.js 20.19+ and npm (required by Vite 7)
- Docker + Docker Compose
- Git

### Quick Start (Docker Compose)

```bash
# 1. Clone the repository
git clone https://github.com/BenWYS06/Airline-Mircoservice.git
cd Airline-Mircoservice

# 2. Set required environment variables (copy and fill in)
cp docker/.env.example docker/.env

# 3. Build all service Docker images locally (Jib — no Dockerfile needed)
cd backend
mvn clean package jib:dockerBuild -DskipTests
cd ..

# 4. Start all services
docker compose -f docker/docker-compose.yml up -d

# 5. Check health
curl http://localhost:5000/actuator/health
```

The API is available at `http://localhost:5000`.

### Local Development (individual services)

```bash
# Start identity, data, messaging, discovery, and configuration infrastructure
docker compose -f docker/docker-compose.yml up -d keycloak keycloakdb userdb redis kafka config-server service-registry

# Create all service databases in your local MySQL (localhost:3306), once
mysql -u root -p < docker/init-databases.sql

# Then start individual services from their directory
# (DB_PASSWORD = your local MySQL root password; defaults to "root")
cd backend/services/booking-service
DB_PASSWORD=<your-mysql-password> mvn spring-boot:run
```

### Frontend

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173
npm run build      # production build → frontend/dist
npm run lint       # ESLint
```

### Environment Variables

Copy `docker/.env.example` to `docker/.env` (never commit `.env`). Docker Compose reads it
automatically; for services started with `mvn spring-boot:run`, export the same variables.

```env
# Database (local runs; Docker Compose sets its own)
DB_PASSWORD=<your-local-mysql-root-password>

# Email (Gmail SMTP)
MAIL_USERNAME=your-email@gmail.com
MAIL_APP_PASSWORD=xxxx-xxxx-xxxx-xxxx   # Gmail App Password (not regular password)

# SMS (Twilio)
TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_FROM_NUMBER=+1xxxxxxxxxx
TWILIO_ENABLED=true

# Payment — Razorpay
RAZORPAY_KEY_ID=<your-razorpay-key-id>
RAZORPAY_KEY_SECRET=<your-razorpay-key-secret>

# Payment — Stripe
STRIPE_API_KEY=<your-stripe-secret-key>

# Keycloak / gateway
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=replace-this-password
INTERNAL_API_KEY=replace-with-a-long-random-shared-secret
KEYCLOAK_ISSUER_URI=http://localhost:8180/realms/ben-airline
KEYCLOAK_JWK_SET_URI=http://localhost:8180/realms/ben-airline/protocol/openid-connect/certs
KEYCLOAK_CLIENT_ID=airline-api
```

The main Compose file imports `docker/keycloak/realm-export.json`, which creates
the `ben-airline` realm, the `airline-api` resource client, the public PKCE frontend
client, and the three application roles. Create users in Keycloak (or enable the desired
registration flow) and assign `ROLE_SYSTEM_ADMIN` or `ROLE_AIRLINE_OWNER` where needed;
users without either role are mapped to `ROLE_CUSTOMER` by the profile service.
Email verification is required before the gateway will create or link a local profile.
Configure SMTP in Keycloak for self-registration, or mark administrator-created local
development users as email-verified in the Keycloak console.

The gateway and user service must receive the same `INTERNAL_API_KEY`. In production,
keep the user service on a private network and use a secret manager rather than the
development fallback value. If Keycloak is hosted at another public URL, set the issuer
to that public URL and set the JWK URL to an address reachable by the gateway.

Existing rows in `airline_user.users` are linked on first login when their email matches
the verified Keycloak email. After all accounts are linked and rollback is no longer
needed, the legacy `password` database column can be dropped; it is no longer mapped or
read by application code.

---

## Configuration

All service configuration is managed by the **Config Server**, which clones this
repository and reads the `config-repo/` folder (branch `main`):

```yaml
spring.cloud.config.server.git:
  uri: ${CONFIG_GIT_URI:https://github.com/BenWYS06/Airline-Mircoservice}
  default-label: ${CONFIG_GIT_LABEL:main}
  search-paths: config-repo
```

Config changes only take effect after they are pushed to `main`. To test changes before
pushing, point the Config Server at your local clone and branch:

```bash
cd backend/cloud/config-server
CONFIG_GIT_URI=file:///D:/path/to/this/repo CONFIG_GIT_LABEL=<your-branch> mvn spring-boot:run
```

Each service bootstraps with:
```yaml
spring:
  config:
    import: optional:configserver:http://localhost:8888
```

`optional:` means if the Config Server is unavailable, the service falls back to its local `application.yaml`.

### Circuit Breaker Thresholds

| Client | Failure Threshold | Wait (open state) | Notes |
|---|---|---|---|
| Default | 50% | 30s | All clients inherit this |
| FlightClient | 40% | 45s | Stricter — search must be reliable |
| PaymentClient | 30% | 60s | Very strict — payment is critical |
| AncillaryClient | 50% | 30s | Default |
| PricingClient | 50% | 30s | Default |
| SeatClient | 50% | 30s | Default |
| UserClient | 50% | 30s | Default |

---

## Known Limitations

| Limitation | Impact | Workaround |
|---|---|---|
| `totalElements` overcounts on price filter | Pagination shows higher count than results | Post-filter page normalisation (planned) |
| Seat availability not validated during search | Race condition possible at high concurrency | Optimistic lock on `SeatInstance.version` at booking time |
| No distributed transactions (2PC) | Partial failures require compensating actions | Saga choreography via Kafka; status-based compensation |
| Price filter reduces page size | Page may have fewer results than `pageSize` | Documented; client should handle sparse pages |
| Config Server is a single point of failure | All services need it on first start | `optional:` config import; local YAML fallback |
| Kafka is single broker | No replication in Docker Compose | Increase replicas in Kubernetes deployment |
| Notification failure is silent | Email/SMS may not be delivered | Dead-letter queue (DLQ) planned for Kafka |

---

*Built with Spring Boot 4.0.2 · Spring Cloud 2025.1.0 · Apache Kafka 4.1.1 · MySQL 8.0 · React 19 · Vite 7*
