# frontend

> Loaded when working inside `frontend/`. Folder rules: [.claude/rules/frontend.md](../.claude/rules/frontend.md).

## Stack

React 19 + Vite 7, Redux Toolkit, React Router, Tailwind 4 + shadcn/ui (`src/components/ui`), Formik + Yup, lucide icons. Mostly plain JavaScript (`.jsx`); a few `.tsx` files remain from the original template (auth pages, icons) and Vite builds them as they are.

## Layout

| Path | What |
|---|---|
| `src/main.jsx` | Mounts `<App />` inside the Redux `Provider` |
| `src/App.jsx` | All routes: auth, traveler (`/search`, `/booking-review`, `/booking-success/:bookingId`, `/bookings`…), `/airline/*`, `/super-admin/*` |
| `src/Redux/<domain>/` | One thunk file + one slice per domain; store in `globleState.js` |
| `src/utils/api.js` | Axios instance, base URL `http://localhost:5000` (the gateway) |
| `src/utils/getHeaders.js` | `Authorization: Bearer <jwt>` from `localStorage` |
| `src/pages/traveler/` | Search, seat selection, booking review, payment result, booking history, tickets |
| `src/pages/airline/` | Airline owner dashboard: flights, schedules, fares, seat maps, ancillaries, bookings |
| `src/pages/super-admin/` | Airlines approval, cities, airports, users |
| `src/constants/bookingStatus.js` | Booking status → label, colour, allowed actions (single source) |
| `src/components/booking/BookingStatusBadge.jsx` | The only way to show a booking status |

## Booking flow rules (DESIGN.md §12)

- `POST /api/bookings` answers **201** (new), **202** (same request still processing: wait 2 s and send the same request again, up to ~60 s), **200 + `reused`** (same request already done: offer "Continue previous booking" or "Cancel it and book again"), **409** (seat taken: show the message, clear the selection, reload the seat map).
- Redirect to VNPay with `checkoutUrl`. The result page (`/booking-success/:bookingId`) re-reads the booking; the status, not the URL parameters, decides what is shown.
- "Continue payment" only while `canContinuePayment(booking)` is true (`AWAITING_PAYMENT` and `paymentExpiresAt` in the future).

## Known gaps

- Login and sign-up thunks (`Redux/auth/authThunk.js`) still call the original project's `/auth/*` endpoints. The backend now authenticates with Keycloak at the gateway, so the frontend needs a Keycloak (OIDC) login. Tracked in [TODO.md](../TODO.md).
- The API base URL is hard-coded in `src/utils/api.js`; move it to `VITE_API_URL` when deployment starts.

## Verify

```bash
cd frontend
npm run build
npm run lint      # report only new errors; old ones are listed in the PR, not fixed in passing
```
