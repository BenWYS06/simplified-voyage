# Runbook: run the stack locally

Two ways to run the system. Background on ports and variables: [environments.md](../environments.md).

## A. Infra in Docker, services from the IDE (daily work)

1. **Start the infra**
   ```bash
   docker compose -f docker/docker-compose.dev.yml up -d
   docker compose -f docker/docker-compose.dev.yml ps        # all "running" / "healthy"
   ```
   MySQL (:3306) creates every service database on its first start (`docker/init-databases.sql`). PostgreSQL (:5432) holds `airline_booking_db`. Redis :6379, Kafka :9092.

2. **Keycloak** (only if you need real logins)
   ```bash
   docker compose -f docker/docker-compose.yml up -d keycloakdb keycloak
   ```
   Admin console http://localhost:8180, realm `ben-airline` is imported on first start.

3. **Platform services**, in this order, each from its folder (`mvn spring-boot:run`) or the IDE:
   1. `backend/cloud/config-server` → http://localhost:8888/booking-service/default returns JSON
   2. `backend/cloud/service-registry` → http://localhost:8761 shows the Eureka dashboard
   3. `backend/cloud/api-gateway` → http://localhost:5000/actuator/health is `UP`

4. **Business services** you need. For the booking flow: `user-service`, `flight-ops-service`, `seat-service`, `pricing-service`, `ancillary-service`, `airline-core-service`, `location-service`, `booking-service`, `payment-service`, `notification-service`.
   - MySQL services read `DB_PASSWORD` (default `root`); booking-service uses `postgres` / `postgres`.
   - Each service runs its Flyway migrations on start; the log shows `Successfully applied N migrations` the first time.
   - Check registration: every service appears in Eureka within ~30 s.

5. **Frontend**
   ```bash
   cd frontend && npm install && npm run dev    # http://localhost:5173
   ```

## B. Everything in Docker

```bash
cd backend && mvn clean package jib:dockerBuild -DskipTests && cd ..
docker compose -f docker/docker-compose.yml up -d
docker compose -f docker/docker-compose.yml ps
curl http://localhost:5000/actuator/health
```
To make config-server read your local, uncommitted `config-repo/` instead of GitHub:
```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.local-config.yml up -d
```
After a code change, rebuild only that image (`mvn -pl services/<service> -am package jib:dockerBuild -DskipTests`) and `docker compose up -d <service>`.

## Call a service without Keycloak

Call the service port directly (from Eureka) with the trusted headers the gateway would set:
```
GET http://localhost:<booking-port>/api/bookings/user/history
X-User-Id: 1
X-User-Roles: ROLE_CUSTOMER
```

## Walk the booking flow

1. As an airline owner: airline → aircraft + seat map → flight → flight instance (seat instances are generated through `flight-instance-created`) → fares.
2. As a customer: search, pick seats, `POST /api/bookings` → `201` with `checkoutUrl`.
3. Pay in the VNPay sandbox, or simulate the IPN without ngrok: [VNPAY.md](../booking-refactor/VNPAY.md).
4. Check: booking `CONFIRMED`, seats `BOOKED`, tickets issued, email logged by notification-service.

## Stop and clean

```bash
docker compose -f docker/docker-compose.dev.yml down          # keep data
docker compose -f docker/docker-compose.dev.yml down -v       # also delete the DB volumes (data is lost)
```
