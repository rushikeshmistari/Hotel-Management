/* =====================================================================
   Verdure front end. All data lives on the Java server (Server.java);
   this file only calls its /api/* endpoints and renders the results.
   ===================================================================== */

const CUSTOMER_TOKEN_KEY = 'verdure_customer_token';
const ADMIN_TOKEN_KEY = 'verdure_admin_token';

const GALLERY = [
  { label: 'Lobby',      seed: 'verdure-lobby' },
  { label: 'Garden',     seed: 'verdure-garden' },
  { label: 'Suite',      seed: 'verdure-suite' },
  { label: 'Restaurant', seed: 'verdure-restaurant' },
  { label: 'Pool',       seed: 'verdure-pool' },
  { label: 'Courtyard',  seed: 'verdure-courtyard' },
];

/* ---------- State ---------- */
let rooms = [];
let myBookings = [];
let adminBookings = [];
let adminMessages = [];
let currentCustomer = null;
let pendingRoomId = null;

/* ---------- Helpers ---------- */
const $ = (id) => document.getElementById(id);
const money = (n) => '₹' + Number(n).toLocaleString('en-IN');
function escapeHTML(str){
  const div = document.createElement('div');
  div.textContent = str == null ? '' : String(str);
  return div.innerHTML;
}
function fmtDate(iso){
  if(!iso) return '';
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
function showToast(msg){
  const toast = $('toast');
  toast.textContent = msg;
  toast.classList.add('is-visible');
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => toast.classList.remove('is-visible'), 3200);
}

/** Call the Java API. Throws an Error (with .status) carrying the server's message. */
async function api(path, { method = 'GET', body, token } = {}){
  const headers = {};
  if(body !== undefined) headers['Content-Type'] = 'application/json';
  if(token) headers['Authorization'] = 'Bearer ' + token;
  let res;
  try{
    res = await fetch('/api/' + path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  } catch(e){
    throw new Error('Can\'t reach the server. Is the Java backend running?');
  }
  const data = await res.json().catch(() => ({}));
  if(!res.ok){
    const err = new Error(data.error || 'Request failed.');
    err.status = res.status;
    throw err;
  }
  return data;
}
const customerApi = (path, opts = {}) => api(path, { ...opts, token: sessionStorage.getItem(CUSTOMER_TOKEN_KEY) });
const adminApi = (path, opts = {}) => api(path, { ...opts, token: sessionStorage.getItem(ADMIN_TOKEN_KEY) });

/* Disable a button while an async action runs */
async function withBusy(btn, fn){
  if(btn) btn.disabled = true;
  try{ return await fn(); }
  finally{ if(btn) btn.disabled = false; }
}

/* =====================================================================
   Nav
   ===================================================================== */
const navEl = document.querySelector('.nav');
$('navBurger').addEventListener('click', () => {
  const open = navEl.classList.toggle('is-open');
  $('navBurger').setAttribute('aria-expanded', String(open));
});
document.querySelectorAll('.nav__link').forEach(l => l.addEventListener('click', () => navEl.classList.remove('is-open')));

const navLinks = document.querySelectorAll('.nav__link');
const sectionObserver = new IntersectionObserver((entries) => {
  entries.forEach(entry => {
    if(entry.isIntersecting){
      navLinks.forEach(l => l.classList.toggle('is-active', l.dataset.section === entry.target.id));
    }
  });
}, { rootMargin: '-45% 0px -50% 0px' });
document.querySelectorAll('main .section[id]').forEach(s => sectionObserver.observe(s));

/* =====================================================================
   Rooms (public)
   ===================================================================== */
async function loadRooms(){
  try{
    rooms = await api('rooms');
  } catch(e){
    $('roomsGrid').innerHTML = `<p class="empty-note">${escapeHTML(e.message)}</p>`;
    return;
  }
  renderRoomsGrid();
  if(currentCustomer) renderCustomerRoomSelect();
}

function renderRoomsGrid(){
  const grid = $('roomsGrid');
  $('statRooms').textContent = rooms.length;
  if(rooms.length === 0){
    grid.innerHTML = '<p class="empty-note">No rooms are listed right now.</p>';
    return;
  }
  grid.innerHTML = rooms.map(r => `
    <div class="room-card">
      <div class="room-card__top">
        <h3 class="room-card__name">${escapeHTML(r.name)}</h3>
        <div class="room-card__price"><strong>${money(r.price)}</strong><span>per night</span></div>
      </div>
      <div class="room-card__meta">
        <span class="tag">${r.capacity} guests</span>
        ${r.tag ? `<span class="tag">${escapeHTML(r.tag)}</span>` : ''}
        <span class="tag ${r.available ? '' : 'tag--unavailable'}">${r.available ? 'Available' : 'Fully booked'}</span>
      </div>
      <div class="room-card__actions">
        <button class="btn btn--ghost btn--small" data-room-id="${escapeHTML(r.id)}" ${r.available ? '' : 'disabled'}>
          ${r.available ? 'Book this room' : 'Unavailable'}
        </button>
      </div>
    </div>`).join('');

  grid.querySelectorAll('[data-room-id]').forEach(btn => {
    btn.addEventListener('click', () => {
      pendingRoomId = btn.dataset.roomId;
      if(currentCustomer) renderCustomerRoomSelect();
      $('account').scrollIntoView({ behavior: 'smooth' });
    });
  });
}

function renderGallery(){
  $('galleryGrid').innerHTML = GALLERY.map(g => `
    <div class="gallery-tile">
      <img src="https://picsum.photos/seed/${encodeURIComponent(g.seed)}/480/360" alt="${escapeHTML(g.label)} at Verdure" loading="lazy">
      <span>${escapeHTML(g.label)}</span>
    </div>`).join('');
}

/* =====================================================================
   Contact
   ===================================================================== */
$('contactForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('contactError').textContent = '';
  try{
    await withBusy(e.submitter, () => api('contact', { method: 'POST', body: {
      name: $('cfName').value, email: $('cfEmail').value, message: $('cfMsg').value
    }}));
    e.target.reset();
    showToast('Message sent — we\'ll reply within a day.');
  } catch(err){
    $('contactError').textContent = err.message;
  }
});

/* =====================================================================
   Customer account
   ===================================================================== */
const loginForm = $('loginForm');
const signupForm = $('signupForm');

function setAuthTab(tab){
  const isLogin = tab === 'login';
  $('tabLoginBtn').classList.toggle('is-active', isLogin);
  $('tabSignupBtn').classList.toggle('is-active', !isLogin);
  loginForm.classList.toggle('is-active', isLogin);
  signupForm.classList.toggle('is-active', !isLogin);
}
$('tabLoginBtn').addEventListener('click', () => setAuthTab('login'));
$('tabSignupBtn').addEventListener('click', () => setAuthTab('signup'));

function showCustomer(customer){
  currentCustomer = customer;
  $('accountGate').hidden = !!customer;
  $('accountDashboard').hidden = !customer;
  $('navAccountLink').textContent = customer ? 'Dashboard' : 'Account';
  if(!customer){
    myBookings = [];
    $('myBookingsBody').innerHTML = '';
    $('bookingForm').reset();
    $('bookingError').textContent = '';
    return;
  }

  $('welcomeHeading').textContent = `Welcome back, ${customer.name.split(' ')[0]}`;
  $('bfName').value = customer.name;
  $('bfEmail').value = customer.email;
  if(customer.phone) $('bfPhone').value = customer.phone;
  renderCustomerRoomSelect();
  updateSummary();
  loadMyBookings();
}

function customerLoggedIn(data){
  sessionStorage.setItem(CUSTOMER_TOKEN_KEY, data.token);
  showCustomer(data.customer);
}

loginForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  $('loginError').textContent = '';
  try{
    const data = await withBusy(e.submitter, () => api('login', { method: 'POST', body: {
      email: $('liEmail').value, password: $('liPassword').value
    }}));
    loginForm.reset();
    customerLoggedIn(data);
    showToast(`Welcome back, ${data.customer.name.split(' ')[0]}.`);
  } catch(err){
    $('loginError').textContent = err.message;
  }
});

signupForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  $('signupError').textContent = '';
  try{
    const data = await withBusy(e.submitter, () => api('signup', { method: 'POST', body: {
      name: $('suName').value, email: $('suEmail').value,
      phone: $('suPhone').value, password: $('suPassword').value
    }}));
    signupForm.reset();
    customerLoggedIn(data);
    showToast(`Account created — welcome, ${data.customer.name.split(' ')[0]}.`);
  } catch(err){
    $('signupError').textContent = err.message;
  }
});

$('customerLogoutBtn').addEventListener('click', async () => {
  try{ await customerApi('logout', { method: 'POST' }); } catch(e){ /* token may already be gone */ }
  sessionStorage.removeItem(CUSTOMER_TOKEN_KEY);
  myBookings = [];
  showCustomer(null);
  setAuthTab('login');
  showToast('Logged out.');
});

/* ---------- Booking form ---------- */
const bfRoom = $('bfRoom'), bfCheckin = $('bfCheckin'), bfCheckout = $('bfCheckout'), bfGuests = $('bfGuests');
const todayISO = new Date().toISOString().split('T')[0];
bfCheckin.min = todayISO;
bfCheckout.min = todayISO;

function renderCustomerRoomSelect(){
  const available = rooms.filter(r => r.available);
  const previous = bfRoom.value;
  bfRoom.innerHTML = available.length
    ? available.map(r => `<option value="${escapeHTML(r.id)}">${escapeHTML(r.name)} — ${money(r.price)}/night</option>`).join('')
    : '<option value="">No rooms available right now</option>';
  if(pendingRoomId && available.some(r => r.id === pendingRoomId)){
    bfRoom.value = pendingRoomId;
    pendingRoomId = null;
  } else if(available.some(r => r.id === previous)){
    bfRoom.value = previous;
  }
  updateSummary();
}

function nightsBetween(a, b){
  const diff = (new Date(b) - new Date(a)) / 86400000;
  return Number.isFinite(diff) ? Math.round(diff) : 0;
}

function updateSummary(){
  const room = rooms.find(r => r.id === bfRoom.value);
  const nights = nightsBetween(bfCheckin.value, bfCheckout.value);
  if(room) bfGuests.max = room.capacity;
  $('sumRoom').textContent = room ? room.name : '—';
  $('sumNights').textContent = nights > 0 ? nights : '—';
  $('sumRate').textContent = room ? money(room.price) : '—';
  $('sumTotal').textContent = (room && nights > 0) ? money(room.price * nights) : '—';
}
[bfRoom, bfCheckin, bfCheckout].forEach(el => el.addEventListener('change', updateSummary));
bfCheckin.addEventListener('change', () => {
  if(bfCheckin.value) bfCheckout.min = bfCheckin.value;
});

$('bookingForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const errEl = $('bookingError');
  errEl.textContent = '';
  try{
    const booking = await withBusy($('bookingSubmit'), () => customerApi('bookings', { method: 'POST', body: {
      roomId: bfRoom.value,
      checkin: bfCheckin.value,
      checkout: bfCheckout.value,
      guests: bfGuests.value,
      phone: $('bfPhone').value,
      notes: $('bfNotes').value
    }}));
    bfCheckin.value = ''; bfCheckout.value = ''; $('bfNotes').value = '';
    updateSummary();
    await loadMyBookings();
    showToast(`Booking request sent — reference ${booking.id.toUpperCase()}`);
  } catch(err){
    if(err.status === 401) handleCustomerSessionExpired();
    else errEl.textContent = err.message;
  }
});

function handleCustomerSessionExpired(){
  sessionStorage.removeItem(CUSTOMER_TOKEN_KEY);
  showCustomer(null);
  showToast('Your session expired — please log in again.');
}

/* ---------- My bookings ---------- */
async function loadMyBookings(){
  try{
    myBookings = await customerApi('bookings/mine');
  } catch(err){
    if(err.status === 401) handleCustomerSessionExpired();
    return;
  }
  renderMyBookings();
}

function renderMyBookings(){
  const body = $('myBookingsBody');
  const list = myBookings.slice().reverse();
  $('myBookingsEmpty').hidden = list.length > 0;
  body.innerHTML = list.map(b => `
    <tr>
      <td>${escapeHTML(b.roomName)}</td>
      <td>${fmtDate(b.checkin)}</td>
      <td>${fmtDate(b.checkout)}</td>
      <td>${b.guests}</td>
      <td>${money(b.total)}</td>
      <td><span class="status-pill status-pill--${escapeHTML(b.status)}">${escapeHTML(b.status)}</span></td>
      <td class="actions">${b.status === 'pending'
        ? `<button class="btn btn--danger btn--small" data-cancel="${escapeHTML(b.id)}">Cancel</button>` : ''}</td>
    </tr>`).join('');

  body.querySelectorAll('[data-cancel]').forEach(btn => {
    btn.addEventListener('click', async () => {
      try{
        await withBusy(btn, () => customerApi(`bookings/${btn.dataset.cancel}/cancel`, { method: 'POST' }));
        showToast('Booking cancelled.');
      } catch(err){
        showToast(err.message);
      }
      loadMyBookings();
    });
  });
}

/* =====================================================================
   Admin
   ===================================================================== */
function showAdmin(loggedIn){
  $('adminGate').hidden = loggedIn;
  $('adminDashboard').hidden = !loggedIn;
  if(loggedIn) loadAdminData();
}

function handleAdminSessionExpired(){
  sessionStorage.removeItem(ADMIN_TOKEN_KEY);
  showAdmin(false);
  showToast('Admin session expired — please log in again.');
}

$('adminLoginBtn').addEventListener('click', async () => {
  $('adminError').textContent = '';
  try{
    const data = await withBusy($('adminLoginBtn'), () => api('admin/login', { method: 'POST', body: { password: $('adminPass').value } }));
    sessionStorage.setItem(ADMIN_TOKEN_KEY, data.token);
    $('adminPass').value = '';
    showAdmin(true);
  } catch(err){
    $('adminError').textContent = err.message;
  }
});
$('adminPass').addEventListener('keydown', (e) => { if(e.key === 'Enter') $('adminLoginBtn').click(); });
$('adminLogoutBtn').addEventListener('click', () => {
  sessionStorage.removeItem(ADMIN_TOKEN_KEY);
  showAdmin(false);
});

document.querySelectorAll('.admin-tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.admin-tab').forEach(t => t.classList.toggle('is-active', t === tab));
    ['tabBookings', 'tabRooms', 'tabMessages'].forEach(id => { $(id).hidden = tab.dataset.tab !== id; });
  });
});

async function loadAdminData(){
  try{
    [adminBookings, adminMessages] = await Promise.all([adminApi('admin/bookings'), adminApi('admin/messages')]);
    await loadRooms();
  } catch(err){
    if(err.status === 401) handleAdminSessionExpired();
    else showToast(err.message);
    return;
  }
  renderAdmin();
}

function renderAdmin(){
  renderAdminStats();
  renderAdminBookings();
  renderAdminRooms();
  renderAdminMessages();
}

function renderAdminStats(){
  $('statTotalRooms').textContent = rooms.length;
  $('statAvailRooms').textContent = rooms.filter(r => r.available).length;
  $('statTotalBookings').textContent = adminBookings.length;
  $('statPendingBookings').textContent = adminBookings.filter(b => b.status === 'pending').length;
}

/** Run an admin API call, then refresh; handles an expired admin session. */
async function adminAction(fn, successMsg){
  try{
    await fn();
    if(successMsg) showToast(successMsg);
  } catch(err){
    if(err.status === 401){ handleAdminSessionExpired(); return; }
    showToast(err.message);
  }
  await loadAdminData();
}

function renderAdminBookings(){
  const body = $('bookingsTableBody');
  const list = adminBookings.slice().reverse();
  $('bookingsEmpty').hidden = list.length > 0;
  body.innerHTML = list.map(b => `
    <tr>
      <td>${escapeHTML(b.name)}<br><span class="muted" style="font-size:.78rem">${escapeHTML(b.email)} · ${escapeHTML(b.phone)}</span></td>
      <td>${escapeHTML(b.roomName)}</td>
      <td>${fmtDate(b.checkin)}</td>
      <td>${fmtDate(b.checkout)}</td>
      <td>${b.guests}</td>
      <td>${money(b.total)}</td>
      <td>
        <select class="status-select" data-status-for="${escapeHTML(b.id)}">
          ${['pending', 'confirmed', 'cancelled'].map(s =>
            `<option value="${s}" ${b.status === s ? 'selected' : ''}>${s[0].toUpperCase() + s.slice(1)}</option>`).join('')}
        </select>
      </td>
      <td class="actions"><button class="btn btn--danger btn--small" data-delete-booking="${escapeHTML(b.id)}">Delete</button></td>
    </tr>`).join('');

  body.querySelectorAll('[data-status-for]').forEach(sel => {
    sel.addEventListener('change', () => adminAction(
      () => adminApi(`admin/bookings/${sel.dataset.statusFor}`, { method: 'PATCH', body: { status: sel.value } }),
      'Status updated.'));
  });
  body.querySelectorAll('[data-delete-booking]').forEach(btn => {
    btn.addEventListener('click', () => {
      if(!confirm('Delete this booking permanently?')) return;
      adminAction(() => adminApi(`admin/bookings/${btn.dataset.deleteBooking}`, { method: 'DELETE' }), 'Booking deleted.');
    });
  });
}

function renderAdminRooms(){
  const body = $('roomsTableBody');
  body.innerHTML = rooms.map(r => `
    <tr>
      <td>${escapeHTML(r.name)}</td>
      <td>${money(r.price)}</td>
      <td>${r.capacity}</td>
      <td>${escapeHTML(r.tag || '—')}</td>
      <td><span class="status-pill ${r.available ? 'status-pill--confirmed' : 'status-pill--cancelled'}">${r.available ? 'Available' : 'Unavailable'}</span></td>
      <td class="actions">
        <button class="btn btn--ghost btn--small" data-toggle-room="${escapeHTML(r.id)}">${r.available ? 'Mark full' : 'Mark open'}</button>
        <button class="btn btn--danger btn--small" data-delete-room="${escapeHTML(r.id)}">Delete</button>
      </td>
    </tr>`).join('');

  body.querySelectorAll('[data-toggle-room]').forEach(btn => {
    btn.addEventListener('click', () => adminAction(
      () => adminApi(`admin/rooms/${btn.dataset.toggleRoom}`, { method: 'PATCH' })));
  });
  body.querySelectorAll('[data-delete-room]').forEach(btn => {
    btn.addEventListener('click', () => {
      if(!confirm('Delete this room?')) return;
      adminAction(() => adminApi(`admin/rooms/${btn.dataset.deleteRoom}`, { method: 'DELETE' }), 'Room deleted.');
    });
  });
}

function renderAdminMessages(){
  $('messagesEmpty').hidden = adminMessages.length > 0;
  $('messagesTableBody').innerHTML = adminMessages.slice().reverse().map(m => `
    <tr>
      <td>${escapeHTML(m.name)}<br><span class="muted" style="font-size:.78rem">${escapeHTML(m.email)}</span></td>
      <td class="wrap">${escapeHTML(m.message)}</td>
      <td>${fmtDate(m.createdAt)}</td>
    </tr>`).join('');
}

$('roomForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('roomError').textContent = '';
  try{
    const room = await withBusy(e.submitter, () => adminApi('admin/rooms', { method: 'POST', body: {
      name: $('rfName').value, price: $('rfPrice').value,
      capacity: $('rfCapacity').value, tag: $('rfTag').value
    }}));
    e.target.reset();
    showToast(`${room.name} added.`);
    await loadAdminData();
  } catch(err){
    if(err.status === 401) handleAdminSessionExpired();
    else $('roomError').textContent = err.message;
  }
});

/* =====================================================================
   Hero scroll animation (reverses automatically when scrolling up)
   ===================================================================== */
const heroSection = $('home');
const heroLayers = [
  { el: document.querySelector('.hero__kicker'),  depth: -70, fade: 1.5 },
  { el: document.querySelector('.hero__title'),   depth:  40, fade: 1.15 },
  { el: document.querySelector('.hero__sub'),     depth:  60, fade: 1.15 },
  { el: document.querySelector('.hero__actions'), depth:  80, fade: 1.5 },
  { el: document.querySelector('.hero__stats'),   depth: 100, fade: 1.5 },
];
const sceneBg = document.querySelector('.scene-bg');
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
let heroTicking = false;

function applyHeroScroll(){
  heroTicking = false;
  const heroHeight = heroSection.offsetHeight || window.innerHeight;
  const progress = Math.min(Math.max(window.scrollY / heroHeight, 0), 1);
  heroLayers.forEach(({ el, depth, fade }) => {
    if(!el) return;
    el.style.transform = `translateY(${(progress * depth).toFixed(2)}px)`;
    el.style.opacity = Math.max(0, 1 - progress * fade).toFixed(3);
  });
  sceneBg.style.transform = `translateY(${(progress * 25).toFixed(2)}px) scale(${(1 + progress * 0.06).toFixed(3)})`;
}
if(!reducedMotion){
  const onScroll = () => { if(!heroTicking){ heroTicking = true; requestAnimationFrame(applyHeroScroll); } };
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  applyHeroScroll();
}

/* =====================================================================
   Init
   ===================================================================== */
async function init(){
  renderGallery();
  await loadRooms();

  // Restore sessions (tokens live in sessionStorage; the server decides if they're still valid)
  if(sessionStorage.getItem(CUSTOMER_TOKEN_KEY)){
    try{
      showCustomer(await customerApi('me'));
    } catch(err){
      sessionStorage.removeItem(CUSTOMER_TOKEN_KEY);
    }
  }
  if(sessionStorage.getItem(ADMIN_TOKEN_KEY)) showAdmin(true);
}
init();
