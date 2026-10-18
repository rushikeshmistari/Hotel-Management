# Verdure Hotel — HTML/CSS/JS front end + Java back end

```
hotel-site/
├── Server.java          Java back end (REST API + serves the website)
└── public/
    ├── verdure.html     front end: page structure
    ├── verdure.css      front end: styling
    └── verdure.js       front end: calls the Java API with fetch()
```

## Run it

Requires **Java 17 or newer** (no Maven, no libraries to install).

```bash
cd hotel-site
java Server.java
```

Then open **http://localhost:8080** in Chrome.

> Open it through the server URL, not by double-clicking `verdure.html` —
> the page needs the Java API to load rooms, log in and book.

Optional settings: `PORT=9000 ADMIN_PASSWORD=mysecret java Server.java`
Default admin password is `verdure123`.

Data is saved to `data.json` next to `Server.java` (created on first run).
Delete that file to reset everything.

## What the Java back end does

- Stores rooms, customers, bookings and contact messages in `data.json`
- Customer sign-up / log-in (passwords hashed with PBKDF2 + random salt)
- Token-based sessions; customer and admin routes are protected separately
- Validates every booking on the server: dates, guest count vs. room capacity,
  and blocks double-booking of the same room on overlapping dates
- Calculates the booking total itself (the browser can't set the price)

## API

| Method | Endpoint | Who |
|---|---|---|
| GET | `/api/rooms` | public |
| POST | `/api/signup`, `/api/login`, `/api/contact` | public |
| GET | `/api/me` | customer |
| POST | `/api/logout` | customer |
| POST | `/api/bookings` | customer |
| GET | `/api/bookings/mine` | customer |
| POST | `/api/bookings/{id}/cancel` | customer (own, pending only) |
| POST | `/api/admin/login` | public |
| GET | `/api/admin/bookings`, `/api/admin/messages` | admin |
| PATCH / DELETE | `/api/admin/bookings/{id}` | admin |
| POST | `/api/admin/rooms` | admin |
| PATCH / DELETE | `/api/admin/rooms/{id}` | admin |
