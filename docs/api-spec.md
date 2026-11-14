# API reference

Every HTTP endpoint the services expose, generated from the controllers and checked by hand. Request and response DTOs live in `backend/common-lib/src/main/java/com/ben/common_lib/payload/`.

## Conventions

- **Base URL:** everything public goes through the gateway, `http://localhost:5000`. Services are never called directly from the browser.
- **Auth:** `Authorization: Bearer <Keycloak access token>`. The gateway validates it and forwards `X-User-Id` and `X-User-Roles`; services trust only those headers ([authentication.md](authentication.md)).
- **Access column:**
  - **any user**: any valid token;
  - **customer** / **owner**: `ROLE_CUSTOMER` / `ROLE_AIRLINE_OWNER` via `@RequiredRoles` (`ROLE_SYSTEM_ADMIN` always passes);
  - **admin**: `ROLE_SYSTEM_ADMIN` only;
  - **public**: no token;
  - **internal**: `/internal/**`, service to service only, never routed by the gateway.
- Endpoints that return one user's data also check ownership (for example `GET /api/bookings/{id}` returns 403 for someone else's booking).
- **Errors:** `400` validation, `401` no or bad token (gateway), `403` wrong role or not the owner, `404` not found, `409` conflict (seat taken, booking can't change state), `503` a dependency is down (Feign fallback).
- **Changing an endpoint:** follow `.claude/rules/api-contract.md` and update this file in the same PR.

## Booking flow endpoints in detail

### `POST /api/bookings` (customer)

Creates a booking, holds the seats and returns the VNPay link. Safe to send twice: the same user, flight and seat set map to one live booking.

| Status | Meaning | Frontend does |
|---|---|---|
| `201` | New booking, `checkoutUrl` ready | Redirect to VNPay |
| `202` | The same request is still being processed | Wait ~2 s, send the same request again |
| `200` + `reused: true` | The same request already finished | Offer "Continue previous booking" / "Cancel it and book again" |
| `409` | A seat was taken by someone else | Show the message, clear the selection, reload the seat map |
| `503` | seat, pricing or payment service unavailable | "Try again" |

Response: `PaymentInitiateResponse` (`bookingId`, `bookingStatus`, `paymentId`, `checkoutUrl`, `expiresAt`, `amount`, `currency`, `reused`, `success`, `message`).

### `GET /api/payments/vnpay/ipn` (public, signed)

VNPay's server-to-server result. Verified by HMAC-SHA512 and amount, processed under a row lock, only for a `PENDING` payment. Answers VNPay `RspCode`: `00` ok, `01` order not found, `02` already processed, `04` wrong amount, `97` bad signature, `99` error (VNPay retries).

### `POST /api/payments/vnpay/return` (any user)

The browser's return from VNPay, forwarded by the result page with the same query string. Display only, except code `24` (user cancelled) which fails the payment and cancels the booking.

### Internal seat endpoints (booking → seat)

| Method | Path | Result |
|---|---|---|
| POST | `/internal/seat-instances/hold` | All requested seats `HELD` for the booking, or `409` and nothing held |
| POST | `/internal/seat-instances/bookings/{bookingId}/confirm` | All the booking's seats `BOOKED`, or `409` and nothing changes |
| POST | `/internal/seat-instances/bookings/{bookingId}/release` | The booking's `HELD` seats back to `AVAILABLE` (idempotent) |

## Kafka events

| Topic | Producer | Consumers | Key |
|---|---|---|---|
| `flight-instance-created` | flight-ops | seat (build cabins and seat instances) | none |
| `payment.completed`, `payment.failed` | payment (outbox) | booking | bookingId |
| `booking.confirmed` | booking (outbox) | notification | bookingId |
| `booking.cancelled` | booking (outbox) | seat (release) | bookingId |

Every consumer retries with backoff and then sends the message to `<topic>.DLT`. Consumers are idempotent (state guards in the database; notification uses Redis per booking and channel).

## All endpoints by service

### airline-core-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/aircrafts` | owner | `AircraftController.createAircraft` |
| GET | `/api/aircrafts/{id}` | any user | `AircraftController.getAircraftById` |
| GET | `/api/aircrafts` | owner | `AircraftController.listAllAircrafts` |
| PUT | `/api/aircrafts/{id}` | owner | `AircraftController.updateAircraft` |
| DELETE | `/api/aircrafts/{id}` | owner | `AircraftController.deleteAircraft` |
| POST | `/api/airlines` | owner | `AirlineController.createAirline` |
| GET | `/api/airlines/admin` | any user | `AirlineController.getAirlineByOwner` |
| GET | `/api/airlines/{id}` | any user | `AirlineController.getAirlineById` |
| GET | `/api/airlines` | admin (gateway rule) | `AirlineController.getAllAirlines` |
| GET | `/api/airlines/dropdown` | any user | `AirlineController.getAirlinesForDropdown` |
| PUT | `/api/airlines` | owner | `AirlineController.updateAirline` |
| DELETE | `/api/airlines/{id}` | owner | `AirlineController.deleteAirline` |
| POST | `/api/airlines/{id}/approve` | admin | `AirlineController.approveAirline` |
| POST | `/api/airlines/{id}/suspend` | admin | `AirlineController.suspendAirline` |
| POST | `/api/airlines/{id}/ban` | admin | `AirlineController.banAirline` |

### ancillary-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/ancillaries` | owner | `AncillaryController.create` |
| GET | `/api/ancillaries/{id}` | any user | `AncillaryController.getById` |
| GET | `/api/ancillaries` | owner | `AncillaryController.getAllByAirlineId` |
| PUT | `/api/ancillaries/{id}` | owner | `AncillaryController.update` |
| DELETE | `/api/ancillaries/{id}` | owner | `AncillaryController.delete` |
| POST | `/api/flight-cabin-ancillaries` | owner | `FlightCabinAncillaryController.create` |
| POST | `/api/flight-cabin-ancillaries/bulk` | owner | `FlightCabinAncillaryController.bulkCreate` |
| GET | `/api/flight-cabin-ancillaries/{id}` | any user | `FlightCabinAncillaryController.getById` |
| GET | `/api/flight-cabin-ancillaries/all` | any user | `FlightCabinAncillaryController.getAllByIds` |
| GET | `/api/flight-cabin-ancillaries/flight/{flightId}/cabin/{cabinClassId}` | any user | `FlightCabinAncillaryController.getAllByFlightAndCabinClass` |
| GET | `/api/flight-cabin-ancillaries/flight/{flightId}/cabin/{cabinClassId}/type/{type}` | any user | `FlightCabinAncillaryController.getByFlightAndCabinClassAndType` |
| GET | `/api/flight-cabin-ancillaries/flight/{flightId}/cabin/{cabinClassId}/type/{type}/all` | any user | `FlightCabinAncillaryController.getAllByFlightAndCabinClassAndType` |
| PUT | `/api/flight-cabin-ancillaries/{id}` | owner | `FlightCabinAncillaryController.update` |
| DELETE | `/api/flight-cabin-ancillaries/{id}` | owner | `FlightCabinAncillaryController.delete` |
| POST | `/api/flight-cabin-ancillaries/price/total` | any user | `FlightCabinAncillaryController.calculateAncillariesPrice` |
| POST | `/api/flight-meals` | owner | `FlightMealController.createFlightMeal` |
| POST | `/api/flight-meals/bulk` | owner | `FlightMealController.bulkCreateFlightMeals` |
| POST | `/api/flight-meals/price/total` | any user | `FlightMealController.calculateMealPrice` |
| GET | `/api/flight-meals/{id}` | any user | `FlightMealController.getFlightMealById` |
| GET | `/api/flight-meals/flight/{flightId}` | any user | `FlightMealController.getMealsByFlightId` |
| GET | `/api/flight-meals/all` | any user | `FlightMealController.getMealsByIds` |
| PUT | `/api/flight-meals/{id}` | owner | `FlightMealController.updateFlightMeal` |
| PATCH | `/api/flight-meals/{id}/availability` | owner | `FlightMealController.updateFlightMealAvailability` |
| DELETE | `/api/flight-meals/{id}` | owner | `FlightMealController.deleteFlightMeal` |
| POST | `/api/insurance-coverages` | owner | `InsuranceCoverageController.createCoverage` |
| POST | `/api/insurance-coverages/bulk` | owner | `InsuranceCoverageController.createCoveragesBulk` |
| PUT | `/api/insurance-coverages/{id}` | owner | `InsuranceCoverageController.updateCoverage` |
| DELETE | `/api/insurance-coverages/{id}` | owner | `InsuranceCoverageController.deleteCoverage` |
| GET | `/api/insurance-coverages/{id}` | any user | `InsuranceCoverageController.getCoverageById` |
| GET | `/api/insurance-coverages` | any user | `InsuranceCoverageController.getAllCoverages` |
| GET | `/api/insurance-coverages/ancillary/{ancillaryId}` | any user | `InsuranceCoverageController.getCoveragesByAncillaryId` |
| GET | `/api/insurance-coverages/ancillary/{ancillaryId}/active` | any user | `InsuranceCoverageController.getActiveCoveragesByAncillaryId` |
| POST | `/api/meals` | owner | `MealController.createMeal` |
| POST | `/api/meals/bulk` | owner | `MealController.bulkCreateMeals` |
| GET | `/api/meals/{id}` | any user | `MealController.getMealById` |
| GET | `/api/meals/airline` | owner | `MealController.getMealsByAirlineId` |
| PUT | `/api/meals/{id}` | owner | `MealController.updateMeal` |
| PATCH | `/api/meals/{id}/availability` | owner | `MealController.updateMealAvailability` |
| DELETE | `/api/meals/{id}` | owner | `MealController.deleteMeal` |

### booking-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/bookings` | customer | `BookingController.createBooking` |
| GET | `/api/bookings/{id}` | any user | `BookingController.getBookingById` |
| GET | `/api/bookings/airline` | owner | `BookingController.getBookingsByAirline` |
| GET | `/api/bookings/user/history` | customer | `BookingController.getBookingsByUser` |
| PATCH | `/api/bookings/{id}/cancel` | customer | `BookingController.cancelBooking` |
| DELETE | `/api/bookings/{id}` | customer | `BookingController.deleteBooking` |
| GET | `/api/bookings/count/flight/{flightId}` | any user | `BookingController.getBookingCountByFlight` |
| GET | `/api/bookings/statistics/airline` | owner | `BookingController.getBookingStatisticsForAirline` |
| GET | `/api/tickets/{ticketNumber}` | any user | `TicketController.getTicketByNumber` |
| GET | `/api/tickets/booking/{bookingId}` | any user | `TicketController.getTicketsByBooking` |
| GET | `/api/tickets/passenger/{passengerId}` | any user | `TicketController.getTicketsByPassenger` |
| PUT | `/api/tickets/{ticketId}/cancel` | customer | `TicketController.cancelTicket` |
| PUT | `/api/tickets/{ticketId}/use` | owner | `TicketController.markTicketAsUsed` |
| PUT | `/api/tickets/{ticketId}/refund` | owner | `TicketController.refundTicket` |

### flight-ops-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/flights` | owner | `FlightController.createFlight` |
| POST | `/api/flights/bulk` | owner | `FlightController.createFlights` |
| POST | `/api/flights/batch` | any user | `FlightController.getFlightsByIds` |
| GET | `/api/flights/{id}` | any user | `FlightController.getFlightById` |
| GET | `/api/flights/number/{flightNumber}` | any user | `FlightController.getFlightByNumber` |
| GET | `/api/flights/airline` | owner | `FlightController.getFlightsByAirline` |
| PUT | `/api/flights/{id}` | owner | `FlightController.updateFlight` |
| PATCH | `/api/flights/{id}/status` | owner | `FlightController.changeStatus` |
| DELETE | `/api/flights/{id}` | owner | `FlightController.deleteFlight` |
| POST | `/api/flight-instances` | owner | `FlightInstanceController.createFlightInstance` |
| POST | `/api/flight-instances/batch` | any user | `FlightInstanceController.getFlightInstancesByIds` |
| GET | `/api/flight-instances/{id}` | any user | `FlightInstanceController.getFlightInstanceById` |
| GET | `/api/flight-instances/list` | any user | `FlightInstanceController.getFlightInstanceById` |
| GET | `/api/flight-instances` | owner | `FlightInstanceController.getByAirlineId` |
| PUT | `/api/flight-instances/{id}` | owner | `FlightInstanceController.updateFlightInstance` |
| DELETE | `/api/flight-instances/{id}` | owner | `FlightInstanceController.deleteFlightInstance` |
| POST | `/api/flight-schedules` | owner | `FlightScheduleController.createFlightSchedule` |
| GET | `/api/flight-schedules/{id}` | any user | `FlightScheduleController.getFlightScheduleById` |
| GET | `/api/flight-schedules` | owner | `FlightScheduleController.getFlightSchedules` |
| PUT | `/api/flight-schedules/{id}` | owner | `FlightScheduleController.updateFlightSchedule` |
| DELETE | `/api/flight-schedules/{id}` | owner | `FlightScheduleController.deleteFlightSchedule` |
| GET | `/api/flights/search` | any user | `FlightSearchController.searchFlights` |

### location-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/airports` | admin | `AirportController.createAirport` |
| POST | `/api/airports/bulk` | admin | `AirportController.createBulkAirports` |
| GET | `/api/airports/{id}` | any user | `AirportController.getAirportById` |
| GET | `/api/airports` | any user | `AirportController.getAllAirports` |
| GET | `/api/airports/city/{cityId}` | any user | `AirportController.getAirportsByCityId` |
| PUT | `/api/airports/{id}` | admin | `AirportController.updateAirport` |
| DELETE | `/api/airports/{id}` | admin | `AirportController.deleteAirport` |
| POST | `/api/cities` | admin | `CityController.createCity` |
| POST | `/api/cities/bulk` | admin | `CityController.createBulkCities` |
| GET | `/api/cities/{id}` | any user | `CityController.getCityById` |
| GET | `/api/cities` | any user | `CityController.getAllCities` |
| PUT | `/api/cities/{id}` | admin | `CityController.updateCity` |
| DELETE | `/api/cities/{id}` | admin | `CityController.deleteCity` |
| GET | `/api/cities/search` | any user | `CityController.searchCities` |
| GET | `/api/cities/country/{countryCode}` | any user | `CityController.getCitiesByCountryCode` |
| GET | `/api/cities/exists/{cityCode}` | any user | `CityController.checkCityExists` |

### payment-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/payments/initiate` | any user | `PaymentController.initiatePayment` |
| GET | `/api/payments/vnpay/ipn` | public (signed by VNPay) | `PaymentController.vnPayIpn` |
| POST | `/api/payments/vnpay/return` | any user | `PaymentController.vnPayReturn` |
| POST | `/api/payments/batch/bookings` | any user | `PaymentController.getPaymentsByBookingIds` |
| GET | `/api/payments` | admin | `PaymentController.getAllPayments` |

### pricing-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/baggage-policies` | owner | `BaggagePolicyController.createBaggagePolicy` |
| POST | `/api/baggage-policies/bulk` | owner | `BaggagePolicyController.createBaggagePolicies` |
| GET | `/api/baggage-policies/{id}` | any user | `BaggagePolicyController.getBaggagePolicyById` |
| GET | `/api/baggage-policies/fare/{fareId}` | any user | `BaggagePolicyController.getBaggagePolicyByFareId` |
| GET | `/api/baggage-policies/airline/{airlineId}` | any user | `BaggagePolicyController.getBaggagePoliciesByAirlineId` |
| PUT | `/api/baggage-policies/{id}` | owner | `BaggagePolicyController.updateBaggagePolicy` |
| DELETE | `/api/baggage-policies/{id}` | owner | `BaggagePolicyController.deleteBaggagePolicy` |
| POST | `/api/fares` | owner | `FareController.createFare` |
| POST | `/api/fares/bulk` | owner | `FareController.createFares` |
| GET | `/api/fares` | any user | `FareController.getFares` |
| GET | `/api/fares/{id}` | any user | `FareController.getFareById` |
| GET | `/api/fares/{id}/details` | any user | `FareController.getFareByIdWithDetails` |
| GET | `/api/fares/flight/{flightId}` | any user | `FareController.getFaresByFlightId` |
| GET | `/api/fares/flight/{flightId}/details` | any user | `FareController.getFaresByFlightIdWithDetails` |
| GET | `/api/fares/flight/{flightId}/cabin-class/{cabinClassId}` | any user | `FareController.getFaresByFlightAndCabinClass` |
| PUT | `/api/fares/{id}` | owner | `FareController.updateFare` |
| DELETE | `/api/fares/{id}` | owner | `FareController.deleteFare` |
| POST | `/api/fares/batch-by-ids` | any user | `FareController.getFaresByIds` |
| POST | `/api/fares/search` | any user | `FareController.getLowestFarePerFlight` |
| GET | `/api/fares/lowest/flight/{flightId}/cabin-class/{cabinClassId}` | any user | `FareController.getLowestFareForFlightAndCabinClass` |
| POST | `/api/fare-rules` | owner | `FareRulesController.createFareRules` |
| GET | `/api/fare-rules/{id}` | any user | `FareRulesController.getFareRulesById` |
| GET | `/api/fare-rules/fare/{fareId}` | any user | `FareRulesController.getFareRulesByFareId` |
| GET | `/api/fare-rules/airline/{airlineId}` | any user | `FareRulesController.getFareRulesByAirlineId` |
| PUT | `/api/fare-rules/{id}` | owner | `FareRulesController.updateFareRules` |
| DELETE | `/api/fare-rules/{id}` | owner | `FareRulesController.deleteFareRules` |

### seat-service

| Method | Path | Access | Handler |
|---|---|---|---|
| POST | `/api/cabin-classes` | owner | `CabinClassController.createCabinClass` |
| POST | `/api/cabin-classes/create/bulk` | owner | `CabinClassController.createCabinClasses` |
| GET | `/api/cabin-classes/{id}` | any user | `CabinClassController.getCabinClassById` |
| GET | `/api/cabin-classes/aircraft/{id}/name/{cabinClass}` | any user | `CabinClassController.getCabinClassByAircraftIdAndName` |
| GET | `/api/cabin-classes/aircraft/{aircraftId}` | any user | `CabinClassController.getCabinClassesByAircraftId` |
| PUT | `/api/cabin-classes/{id}` | owner | `CabinClassController.updateCabinClass` |
| DELETE | `/api/cabin-classes/{id}` | owner | `CabinClassController.deleteCabinClass` |
| GET | `/api/flight-instance-cabins/{id}` | any user | `FlightInstanceCabinController.getFlightInstanceCabinById` |
| GET | `/api/flight-instance-cabins/flight-instance/{flightInstanceId}/cabin-class/{cabinClassId}` | any user | `FlightInstanceCabinController.getByFlightInstanceIdAndCabinClassId` |
| GET | `/api/flight-instance-cabins/flight-instance/{flightInstanceId}` | any user | `FlightInstanceCabinController.getByFlightInstanceId` |
| PUT | `/api/flight-instance-cabins/{id}` | owner | `FlightInstanceCabinController.updateFlightInstanceCabin` |
| DELETE | `/api/flight-instance-cabins/{id}` | owner | `FlightInstanceCabinController.deleteFlightInstanceCabin` |
| POST | `/internal/seat-instances/hold` | internal | `InternalSeatHoldController.holdSeats` |
| POST | `/internal/seat-instances/bookings/{bookingId}/confirm` | internal | `InternalSeatHoldController.confirmSeats` |
| POST | `/internal/seat-instances/bookings/{bookingId}/release` | internal | `InternalSeatHoldController.releaseSeats` |
| GET | `/api/seats` | any user | `SeatController.getAllSeats` |
| GET | `/api/seats/{id}` | any user | `SeatController.getSeatById` |
| PUT | `/api/seats/{id}` | owner | `SeatController.updateSeat` |
| POST | `/api/seat-instances` | owner | `SeatInstanceController.createSeatInstance` |
| GET | `/api/seat-instances/{id}` | any user | `SeatInstanceController.getSeatInstanceById` |
| POST | `/api/seat-instances/price/total` | any user | `SeatInstanceController.calculateSeatPrice` |
| GET | `/api/seat-instances/flight-instance/{flightInstanceId}` | any user | `SeatInstanceController.getSeatInstancesByFlightInstanceId` |
| GET | `/api/seat-instances/all` | any user | `SeatInstanceController.getAllByIds` |
| GET | `/api/seat-instances/flight-instance/{flightInstanceId}/available` | any user | `SeatInstanceController.getAvailableSeatsByFlightInstanceId` |
| GET | `/api/seat-instances/flight-instance/{flightInstanceId}/available/count` | any user | `SeatInstanceController.countAvailableByFlightInstanceId` |
| PATCH | `/api/seat-instances/{id}/status` | owner | `SeatInstanceController.updateSeatInstanceStatus` |
| POST | `/api/seat-maps` | owner | `SeatMapController.createSeatMap` |
| POST | `/api/seat-maps/create/bulk` | owner | `SeatMapController.createSeatMaps` |
| GET | `/api/seat-maps/{id}` | any user | `SeatMapController.getSeatMapById` |
| GET | `/api/seat-maps/cabin-class/{cabinClassId}` | any user | `SeatMapController.getSeatMapsByCabinClass` |
| PUT | `/api/seat-maps/{id}` | owner | `SeatMapController.updateSeatMap` |
| DELETE | `/api/seat-maps/{id}` | owner | `SeatMapController.deleteSeatMap` |

### user-service

| Method | Path | Access | Handler |
|---|---|---|---|
| GET | `` | any user | `HomeController.HomeTest` |
| POST | `/internal/users/resolve` | internal | `InternalUserController.resolve` |
| GET | `/api/users/profile` | any user | `UserController.getUserProfile` |
| GET | `/api/users/{userId}` | any user | `UserController.getUserById` |
| GET | `/api/users` | admin | `UserController.getUsers` |
