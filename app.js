// The Vercel deployment uses browser storage. The local Java server exposes
  // /api/state and persists the same interface through JDBC + SQLite.
  const STORAGE_KEY = 'mit-league-demo-v1';
  let teams = [];
  let matches = [];
  let history = [];
  let userRole = 'ADMIN';
  let selectedLoginRole = null;
  let nextTeamId = 1;
  let nextMatchId = 1;

  const rosterList = document.getElementById('roster-list');
  const matchTeamA = document.getElementById('match-team-a');
  const matchTeamB = document.getElementById('match-team-b');
  const matchList = document.getElementById('match-list');
  const boardBody = document.getElementById('board-body');
  const heroMini = document.getElementById('hero-mini-standings');
  const heroLeaderName = document.getElementById('hero-leader-name');
  const heroLeaderSub = document.getElementById('hero-leader-sub');
  const formNote = document.getElementById('form-note');
  const scheduleNote = document.getElementById('schedule-note');
  const storageMode = document.getElementById('storage-mode');
  let backendMode = false;
  let saveTimer = null;
  let saveQueue = Promise.resolve();

  function setStorageMode(message, isBackend = false){
    storageMode.textContent = message;
    backendMode = isBackend;
  }

  function saveState(){
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ teams, matches, history })); }
    catch { formNote.textContent = 'Browser storage is unavailable; changes may not survive a reload.'; }
    if(!backendMode) return;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      const payload = JSON.stringify({ teams, matches, history });
      saveQueue = saveQueue.catch(() => {}).then(async () => {
        const response = await fetch('/api/state', { method:'PUT', headers:apiHeaders({'Content-Type':'application/json'}), body:payload });
        if(!response.ok) throw new Error('Database save failed.');
        const saved = await response.json();
        history = saved.history || history;
        renderHistory();
      }).catch(() => {
        setStorageMode('Java database disconnected · using this browser for new changes');
        formNote.textContent = 'Could not save to the tournament database. Changes remain in this browser.';
      });
    }, 180);
  }

  function seedDemo(){
    teams = [
      { id: 1, name: 'Beaver Circuit', college: 'Baker House', game: 'Valorant', captain: 'Priya Nair', rosterSize: 5, members: ['Aarav Shah','Mina Lee','Noah Kim','Sara Das'] },
      { id: 2, name: 'East Campus Ember', college: 'East Campus', game: 'Valorant', captain: 'Diego Ruiz', rosterSize: 5, members: ['Ishaan Roy','Lena Park','Maya Chen','Ravi Kumar'] },
      { id: 3, name: 'Simmons Static', college: 'Simmons Hall', game: 'Valorant', captain: 'Amara Chen', rosterSize: 5, members: ['Zoya Ali','Ethan Lim','Nina Bose','Kabir Sen'] },
    ];
    matches = [
      { id: 1, teamAId: 1, teamBId: 2, scoreA: 13, scoreB: 7, reported: true },
      { id: 2, teamAId: 1, teamBId: 3, scoreA: 13, scoreB: 10, reported: true },
      { id: 3, teamAId: 2, teamBId: 3, scoreA: 9, scoreB: 13, reported: true },
    ];
  }

  function loadLocalState(){
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
      if (saved && Array.isArray(saved.teams) && Array.isArray(saved.matches)) {
        teams = saved.teams; matches = saved.matches; history = saved.history || [];
      } else seedDemo();
    } catch { seedDemo(); }
    nextTeamId = Math.max(0, ...teams.map(t => Number(t.id) || 0)) + 1;
    nextMatchId = Math.max(0, ...matches.map(m => Number(m.id) || 0)) + 1;
    saveState();
  }

  async function initializeState(){
    const isLocalServer = ['localhost','127.0.0.1'].includes(location.hostname);
    if(isLocalServer){
      try {
        const token = sessionStorage.getItem('mit-league-auth');
        if(!token){ showLogin(); return; }
        const response = await fetch('/api/state', {headers:apiHeaders()});
        if(response.status === 401){ sessionStorage.removeItem('mit-league-auth'); showLogin(); return; }
        if(!response.ok) throw new Error('API unavailable');
        userRole = sessionStorage.getItem('mit-league-role') || 'ADMIN';
        const state = await response.json();
        teams = state.teams || [];
        matches = state.matches || [];
        history = state.history || [];
        setStorageMode('Connected to local Java + JDBC database', true);
      } catch {
        loadLocalState();
        userRole = 'ADMIN';
        setStorageMode('Java server unavailable · using this browser');
      }
    } else {
      const demoRole = sessionStorage.getItem('mit-league-static-role');
      if(!demoRole){ showLogin(); return; }
      loadLocalState();
      userRole = demoRole;
      setStorageMode('Browser-only demo · run the Java server to use SQLite');
    }
    teams.forEach(t => { if(!Array.isArray(t.members)) t.members = []; });
    nextTeamId = Math.max(0, ...teams.map(t => Number(t.id) || 0)) + 1;
    nextMatchId = Math.max(0, ...matches.map(m => Number(m.id) || 0)) + 1;
    renderRoster();
    renderMatchSelectors();
    renderMatches();
    renderBoard();
    renderHistory();
    document.getElementById('tournament-app').hidden = false;
    document.getElementById('session-controls').hidden = false;
    document.getElementById('user-badge').textContent = isLocalServer ? `${sessionStorage.getItem('mit-league-user') || userRole} · ${userRole}` : `Demo mode · ${userRole}`;
    const captainView = userRole === 'CAPTAIN';
    const registerView = userRole === 'REGISTER';
    const readOnlyView = userRole !== 'ADMIN';
    document.querySelector('.hero').hidden = registerView;
    document.getElementById('schedule').hidden = registerView;
    document.getElementById('leaderboard').hidden = registerView;
    document.getElementById('menu-btn').hidden = registerView;
    document.getElementById('mobile-links').hidden = registerView;
    document.querySelectorAll('a[href="#schedule"], a[href="#leaderboard"]').forEach(link => { link.hidden = registerView; });
    document.getElementById('hero-lede').textContent = captainView
      ? 'Follow the official fixtures, check reported scores and results, and track the standings as the tournament progresses.'
      : registerView
        ? 'Register your esports team and player roster for the tournament.'
        : 'Register your dorm or club team, publish the match schedule, report scores, and watch the standings update automatically — no spreadsheets required.';
    document.getElementById('schedule-description').textContent = captainView
      ? 'View the official match schedule, live score status, and reported match results.'
      : registerView
        ? 'Only the team registration form and registered team names are available in this role.'
        : 'Pick two registered teams to schedule a match. Once a match is played, report the score to update the standings automatically.';
    document.getElementById('schedule-capability').textContent = readOnlyView ? 'View only' : 'Scorable';
    document.getElementById('register').hidden = captainView;
    document.querySelector('#register .register-grid').classList.toggle('register-only', registerView);
    document.getElementById('team-directory-title').firstChild.textContent = registerView
      ? 'REGISTERED TEAM NAMES ('
      : 'REGISTERED TEAMS (';
    document.querySelectorAll('a[href="#register"], .nav-cta').forEach(link => { link.hidden = captainView; });
    document.getElementById('schedule-admin-controls').hidden = readOnlyView;
    document.getElementById('export-btn').hidden = readOnlyView;
    document.getElementById('reset-btn').hidden = readOnlyView;
  }

  function apiHeaders(extra = {}){
    const headers = {...extra};
    const token = sessionStorage.getItem('mit-league-auth');
    if(token) headers.Authorization = 'Bearer ' + token;
    return headers;
  }

  function showLogin(){
    selectedLoginRole = null;
    document.getElementById('login-role-picker').hidden = false;
    document.getElementById('login-form').hidden = true;
    document.getElementById('login-error').textContent = '';
    const note = document.getElementById('login-mode-note');
    const isLocalServer = ['localhost','127.0.0.1'].includes(location.hostname);
    note.textContent = isLocalServer
      ? 'Local demo credentials: admin / admin123, captain / captain123, or register / register123.'
      : 'Vercel uses browser-only demo access; changes are stored in this browser.';
    document.getElementById('login-screen').hidden = false;
    document.getElementById('tournament-app').hidden = true;
  }

  function openRoleLogin(role){
    selectedLoginRole = role;
    document.getElementById('login-role-picker').hidden = true;
    document.getElementById('login-form').hidden = false;
    const roleLabels = {ADMIN:'Admin Login', CAPTAIN:'Team Login', REGISTER:'Register Team'};
    const roleUsers = {ADMIN:'admin', CAPTAIN:'captain', REGISTER:'register'};
    const demoPasswords = {ADMIN:'admin123', CAPTAIN:'captain123', REGISTER:'register123'};
    document.getElementById('login-heading').textContent = roleLabels[role];
    document.getElementById('login-description').textContent = role === 'ADMIN'
      ? 'Sign in to manage teams, matches, scores and standings.'
      : role === 'CAPTAIN'
        ? 'Sign in for read-only access to the match schedule, scores, results and standings.'
        : 'Sign in to submit a new team and player roster. Existing match data cannot be changed.';
    const username = document.getElementById('login-username');
    username.value = roleUsers[role];
    const password = document.getElementById('login-password');
    document.getElementById('login-error').textContent = '';
    const local = ['localhost','127.0.0.1'].includes(location.hostname);
    password.value = local ? '' : demoPasswords[role];
    document.getElementById('login-note').textContent = local
      ? `Username: ${username.value}. Default password is ${demoPasswords[role]}; local environment settings can override it.`
      : `Vercel demo: username ${username.value}, demo password ${demoPasswords[role]} (prefilled). This is browser-only demo access, not secure authentication.`;
  }

  function computeStandings(){
    const table = teams.map(t => ({ ...t, played:0, wins:0, draws:0, losses:0, points:0 }));
    const byId = Object.fromEntries(table.map(t => [t.id, t]));

    matches.forEach(m => {
      if(!m.reported) return;
      const a = byId[m.teamAId], b = byId[m.teamBId];
      if(!a || !b) return;
      a.played++; b.played++;
      if(m.scoreA > m.scoreB){ a.wins++; a.points += 3; b.losses++; }
      else if(m.scoreB > m.scoreA){ b.wins++; b.points += 3; a.losses++; }
      else { a.draws++; b.draws++; a.points += 1; b.points += 1; }
    });

    return table.sort((x,y) => y.points - x.points || y.wins - x.wins);
  }

  function renderRoster(){
    const count = document.getElementById('team-count');
    if(count) count.textContent = teams.length;
    if(teams.length === 0){
      rosterList.innerHTML = '<div class="empty-state">No teams registered yet — the first one you add will appear here.</div>';
      return;
    }
    rosterList.innerHTML = teams.map(t => userRole === 'REGISTER' ? `
      <div class="roster-item"><div class="name">${escapeHtml(t.name)}</div></div>
    ` : `
      <div class="roster-item">
        <div>
          <div class="name">${escapeHtml(t.name)}</div>
          <div class="meta">${escapeHtml(t.college)} · Capt. ${escapeHtml(t.captain)} · ${t.rosterSize} players</div>
          ${t.members?.length ? `<div class="meta">Roster: ${t.members.map(escapeHtml).join(' · ')}</div>` : ''}
        </div>
        <span class="badge">${escapeHtml(t.game)}</span>
      </div>
    `).join('');
  }

  function renderMatchSelectors(){
    const options = teams.map(t => `<option value="${t.id}">${escapeHtml(t.name)}</option>`).join('');
    matchTeamA.innerHTML = options || '<option value="">Register teams first</option>';
    matchTeamB.innerHTML = options || '<option value="">Register teams first</option>';
  }

  function renderMatches(){
    if(matches.length === 0){
      matchList.innerHTML = '<div class="empty-state">No matches scheduled yet — register at least two teams to get started.</div>';
      return;
    }
    matchList.innerHTML = matches.map((m, i) => {
      const a = teams.find(t => t.id === m.teamAId);
      const b = teams.find(t => t.id === m.teamBId);
      if(!a || !b) return '';
      return `
      <div class="match-card">
        <div class="match-round">MATCH ${i+1}<br><span style="color:var(--steel)">${escapeHtml(a.game)}</span></div>
        <div class="match-team">${escapeHtml(a.name)}</div>
        <div class="vs">VS</div>
        <div class="match-team right">${escapeHtml(b.name)}</div>
        <div class="score-inputs">
          <input type="number" min="0" step="1" aria-label="${escapeHtml(a.name)} score" data-match="${m.id}" data-side="a" value="${m.scoreA ?? ''}" placeholder="0" ${userRole !== 'ADMIN' ? 'disabled' : ''}>
          <input type="number" min="0" step="1" aria-label="${escapeHtml(b.name)} score" data-match="${m.id}" data-side="b" value="${m.scoreB ?? ''}" placeholder="0" ${userRole !== 'ADMIN' ? 'disabled' : ''}>
        </div>
      </div>
      <div style="display:flex; justify-content:flex-end; margin-top:-6px;">
        <span class="status-pill ${m.reported ? 'reported' : 'scheduled'}" data-status="${m.id}">${m.reported ? 'Reported' : 'Scheduled'}</span>
      </div>
      ${userRole === 'ADMIN' ? `<div class="match-admin-actions"><button class="cancel-match-btn" type="button" data-cancel-match="${m.id}">Cancel match</button></div>` : ''}
      `;
    }).join('');

    matchList.querySelectorAll('input[data-match]').forEach(input => {
      input.addEventListener('input', onScoreInput);
    });
    matchList.querySelectorAll('[data-cancel-match]').forEach(button => {
      button.addEventListener('click', () => cancelMatch(Number(button.dataset.cancelMatch)));
    });
  }

  function cancelMatch(matchId){
    if(userRole !== 'ADMIN') return;
    const match = matches.find(item => item.id === matchId);
    if(!match) return;
    const teamA = teams.find(team => team.id === match.teamAId);
    const teamB = teams.find(team => team.id === match.teamBId);
    if(!window.confirm(`Cancel ${teamA?.name || 'Team A'} vs ${teamB?.name || 'Team B'}? The match will be removed from the schedule and standings.`)) return;
    matches = matches.filter(item => item.id !== matchId);
    addLocalHistory(`Match cancelled: ${teamA?.name || 'Team A'} vs ${teamB?.name || 'Team B'}`);
    saveState();
    renderMatches();
    renderBoard();
  }

  function renderHistory(){
    const target = document.getElementById('history-list');
    if(!target) return;
    if(!history.length){ target.innerHTML = '<div class="empty-state" style="padding:12px">Match changes will appear here.</div>'; return; }
    target.innerHTML = history.slice(0,20).map(item => `<div class="history-item"><span>${escapeHtml(item.description || item)}</span><time>${escapeHtml(item.createdAt || '')}</time></div>`).join('');
  }

  function addLocalHistory(description){
    if(backendMode) return;
    history.unshift({description, createdAt:new Date().toLocaleString()});
    history = history.slice(0,20);
    renderHistory();
  }

  function onScoreInput(e){
    if(userRole !== 'ADMIN') return;
    const matchId = Number(e.target.dataset.match);
    const side = e.target.dataset.side;
    const match = matches.find(m => m.id === matchId);
    if(!match) return;
    const val = e.target.value === '' ? null : Number(e.target.value);
    if (val !== null && (!Number.isInteger(val) || val < 0)) {
      e.target.setCustomValidity('Enter a whole score of zero or greater.');
      e.target.reportValidity();
      return;
    }
    e.target.setCustomValidity('');
    if(side === 'a') match.scoreA = val; else match.scoreB = val;
    if(match.scoreA !== null && match.scoreB !== null && !match.reported) addLocalHistory('Result reported for Match ' + match.id + ': ' + match.scoreA + '–' + match.scoreB);
    match.reported = (match.scoreA !== null && match.scoreB !== null && !isNaN(match.scoreA) && !isNaN(match.scoreB));
    const pill = matchList.querySelector(`[data-status="${matchId}"]`);
    if(pill){
      pill.textContent = match.reported ? 'Reported' : 'Scheduled';
      pill.className = 'status-pill ' + (match.reported ? 'reported' : 'scheduled');
    }
    saveState();
    renderBoard();
  }

  function renderBoard(){
    const standings = computeStandings();
    if(standings.length === 0){
      boardBody.innerHTML = '<div class="empty-state" style="padding:26px;">Standings will appear once teams are registered.</div>';
      heroLeaderName.textContent = '—';
      heroLeaderSub.textContent = 'No matches yet';
      heroMini.innerHTML = '';
      return;
    }
    boardBody.innerHTML = standings.map((t, i) => `
      <div class="board-row ${i === 0 ? 'top1' : ''}">
        <div class="pos">${i+1}</div>
        <div class="team"><span class="rank-dot"></span>${escapeHtml(t.name)}</div>
        <div class="num">${t.played}</div>
        <div class="num">${t.wins}</div>
        <div class="num">${t.draws}</div>
        <div class="num pts">${t.points}</div>
      </div>
    `).join('');

    const leader = standings[0];
    heroLeaderName.textContent = leader.name;
    heroLeaderSub.textContent = `${leader.wins}–${leader.losses} · ${leader.points} pts`;

    heroMini.innerHTML = standings.slice(0, 3).map((t, i) => `
      <div class="mini-row">
        <span><span class="pos">${i+1}</span>${escapeHtml(t.name)}</span>
        <span class="pts">${t.points}</span>
      </div>
    `).join('');
  }

  function escapeHtml(str){
    return String(str).replace(/[&<>"']/g, s => ({
      '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
    }[s]));
  }

  document.getElementById('register-form').addEventListener('submit', function(e){
    e.preventDefault();
    if(userRole !== 'ADMIN' && userRole !== 'REGISTER') return;
    const name = document.getElementById('team-name').value.trim();
    const college = document.getElementById('college').value.trim();
    const game = document.getElementById('game').value;
    const captain = document.getElementById('captain').value.trim();
    const rosterSize = Number(document.getElementById('roster-size').value);
    const members = document.getElementById('roster-members').value.split(/[\n,]/).map(name => name.trim()).filter(Boolean);

    if(!name || !college || !game || !captain){ return; }
    if(rosterSize < 1 || rosterSize > 10 || !Number.isInteger(rosterSize)){
      formNote.textContent = 'Roster size must be a whole number from 1 to 10.';
      formNote.classList.add('ok');
      return;
    }
    const uniqueMembers = [...new Map(members.map(member => [member.toLocaleLowerCase(), member])).values()];
    if(uniqueMembers.length !== members.length || uniqueMembers.some(member => member.toLocaleLowerCase() === captain.toLocaleLowerCase())){
      formNote.textContent = 'Player names must be unique and cannot repeat the captain.';
      formNote.classList.add('ok');
      return;
    }
    if(uniqueMembers.length > rosterSize - 1){
      formNote.textContent = `Add at most ${rosterSize - 1} player names; the captain counts as one roster spot.`;
      formNote.classList.add('ok');
      return;
    }
    if(teams.some(t => t.name.toLocaleLowerCase() === name.toLocaleLowerCase() && t.game === game)){
      formNote.textContent = `A ${game} team named “${name}” is already registered.`;
      formNote.classList.add('ok');
      return;
    }

    teams.push({ id: nextTeamId++, name, college, game, captain, rosterSize, members: uniqueMembers });
    this.reset();
    document.getElementById('roster-size').value = 5;
    document.getElementById('roster-members').value = '';
    formNote.textContent = `${name} registered successfully.`;
    formNote.classList.add('ok');
    saveState();

    renderRoster();
    renderMatchSelectors();
    renderBoard();
  });

  document.getElementById('add-match-btn').addEventListener('click', function(){
    if(userRole !== 'ADMIN') return;
    const aId = Number(matchTeamA.value);
    const bId = Number(matchTeamB.value);
    if(!aId || !bId){ scheduleNote.textContent = 'Register at least two teams before scheduling a match.'; scheduleNote.classList.add('error'); return; }
    if(aId === bId){ scheduleNote.textContent = 'Choose two different teams.'; scheduleNote.classList.add('error'); return; }
    const a = teams.find(t => t.id === aId), b = teams.find(t => t.id === bId);
    if(a.game !== b.game){ scheduleNote.textContent = 'Teams must play the same game to be matched.'; scheduleNote.classList.add('error'); return; }
    scheduleNote.textContent = `${a.name} vs ${b.name} scheduled.`;
    scheduleNote.classList.remove('error');
    matches.push({ id: nextMatchId++, teamAId: aId, teamBId: bId, scoreA: null, scoreB: null, reported: false });
    addLocalHistory('Match scheduled: ' + a.name + ' vs ' + b.name);
    saveState();
    renderMatches();
    renderBoard();
  });

  document.getElementById('export-btn').addEventListener('click', function(){
    const rows = [['Rank','Team','Game','Played','Wins','Draws','Losses','Points'], ...computeStandings().map((t,i) => [i+1,t.name,t.game,t.played,t.wins,t.draws,t.losses,t.points])];
    const csv = rows.map(row => row.map(value => {
      const safeValue = typeof value === 'string' && /^[=+@\-]/.test(value) ? `'${value}` : value;
      return `"${String(safeValue).replace(/"/g,'""')}"`;
    }).join(',')).join('\r\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url; link.download = 'mit-league-standings.csv'; link.click();
    URL.revokeObjectURL(url);
  });

  document.getElementById('reset-btn').addEventListener('click', function(){
    if(!window.confirm('Reset teams and matches to the sample demo data?')) return;
    seedDemo();
    nextTeamId = Math.max(0, ...teams.map(t => Number(t.id) || 0)) + 1;
    nextMatchId = Math.max(0, ...matches.map(m => Number(m.id) || 0)) + 1;
    saveState();
    renderRoster(); renderMatchSelectors(); renderMatches(); renderBoard();
    formNote.textContent = 'Demo data has been restored.';
  });

  document.getElementById('login-form').addEventListener('submit', async function(e){
    e.preventDefault();
    const username = document.getElementById('login-username').value.trim();
    const password = document.getElementById('login-password').value;
    const error = document.getElementById('login-error');
    error.textContent = '';
    try {
      const isLocalServer = ['localhost','127.0.0.1'].includes(location.hostname);
      if(!isLocalServer){
        const expectedUser = {ADMIN:'admin', CAPTAIN:'captain', REGISTER:'register'}[selectedLoginRole];
        const expectedPassword = {ADMIN:'admin123', CAPTAIN:'captain123', REGISTER:'register123'}[selectedLoginRole];
        if(username !== expectedUser || password !== expectedPassword) throw new Error('Username or password is incorrect for this role.');
        sessionStorage.setItem('mit-league-static-role', selectedLoginRole);
        sessionStorage.setItem('mit-league-user', username);
        document.getElementById('login-screen').hidden = true;
        document.getElementById('login-password').value = '';
        await initializeState();
        return;
      }
      const response = await fetch('/api/auth/login', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password})});
      const contentType = response.headers.get('content-type') || '';
      if(!contentType.toLowerCase().includes('application/json')){
        const serverMessage = (await response.text()).trim().slice(0,100);
        throw new Error(response.status === 404
          ? 'Sign-in is only available on the local Java app. The Vercel site is the browser-only demo.'
          : `The server returned a non-JSON response${serverMessage ? ': ' + serverMessage : '.'}`);
      }
      const result = await response.json();
      if(!response.ok) throw new Error(result.error || 'Sign-in failed.');
      if(result.role !== selectedLoginRole) {
        const roleName = {ADMIN:'Admin', CAPTAIN:'Team', REGISTER:'Register Team'}[result.role] || 'correct';
        throw new Error(`Use the ${roleName} Login for this account.`);
      }
      sessionStorage.setItem('mit-league-auth', result.token);
      sessionStorage.setItem('mit-league-role', result.role);
      sessionStorage.setItem('mit-league-user', result.username);
      document.getElementById('login-screen').hidden = true;
      document.getElementById('login-password').value = '';
      await initializeState();
    } catch (failure) { error.textContent = failure.message || 'Could not sign in to the local Java server.'; }
  });

  document.querySelectorAll('[data-login-role]').forEach(button => button.addEventListener('click', () => openRoleLogin(button.dataset.loginRole)));
  document.getElementById('login-back').addEventListener('click', showLogin);

  document.getElementById('password-toggle').addEventListener('click', function(){
    const password = document.getElementById('login-password');
    const show = password.type === 'password';
    password.type = show ? 'text' : 'password';
    this.textContent = show ? 'Hide' : 'Show';
    this.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
    this.setAttribute('aria-pressed', String(show));
  });

  document.getElementById('logout-btn').addEventListener('click', async function(){
    if(['localhost','127.0.0.1'].includes(location.hostname)){
      try { await fetch('/api/auth/logout', {method:'POST',headers:apiHeaders()}); } catch {}
    }
    sessionStorage.removeItem('mit-league-auth');
    sessionStorage.removeItem('mit-league-role');
    sessionStorage.removeItem('mit-league-user');
    sessionStorage.removeItem('mit-league-static-role');
    showLogin();
  });

  document.getElementById('menu-btn').addEventListener('click', function(){
    const links = document.getElementById('mobile-links');
    const open = links.classList.toggle('open');
    this.setAttribute('aria-expanded', String(open));
    this.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
  });
  document.querySelectorAll('#mobile-links a').forEach(link => link.addEventListener('click', () => {
    document.getElementById('mobile-links').classList.remove('open');
    document.getElementById('menu-btn').setAttribute('aria-expanded', 'false');
  }));

  initializeState();
