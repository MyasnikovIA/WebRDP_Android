(function () {
  const loginScreen = document.getElementById('login-screen');
  const wrap = document.getElementById('wrap');
  const loginError = document.getElementById('login-error');
  const btnConnect = document.getElementById('btn-connect');
  const btnDisconnect = document.getElementById('btn-disconnect');
  
  const inpIp = document.getElementById('inp-ip');
  const inpPort = document.getElementById('inp-port');
  const inpUser = document.getElementById('inp-user');
  const inpPass = document.getElementById('inp-pass');

  const video = document.getElementById('video');
  const status = document.getElementById('status');
  const logEl = document.getElementById('log');
  const dropZone = document.getElementById('drop-zone');
  const log = (s) => { if (logEl) logEl.textContent = s; };

  let pc, dc;
  let baseUrl = '';
  let authHeader = '';
  let iceInterval = null;
  let currentClientId = '';

  const isRemoteMode = loginScreen !== null;

  if (isRemoteMode) {
    inpIp.value = localStorage.getItem('rdp_ip') || '192.168.1.100';
    inpPort.value = localStorage.getItem('rdp_port') || '8085';
    inpUser.value = localStorage.getItem('rdp_user') || 'admin';
    inpPass.value = localStorage.getItem('rdp_pass') || '';

    btnConnect.addEventListener('click', async () => {
      const ip = inpIp.value.trim();
      const port = inpPort.value.trim();
      const user = inpUser.value.trim();
      const pass = inpPass.value;

      if (!ip || !port) { showError('Укажите IP и порт'); return; }

      localStorage.setItem('rdp_ip', ip);
      localStorage.setItem('rdp_port', port);
      localStorage.setItem('rdp_user', user);
      localStorage.setItem('rdp_pass', pass);

      baseUrl = `http://${ip}:${port}`;
      authHeader = 'Basic ' + btoa(user + ':' + pass);
      currentClientId = 'client_' + Math.random().toString(36).substr(2, 9);

      loginError.style.display = 'none';
      btnConnect.textContent = 'Подключение...';

      try {
        await startConnection();
        loginScreen.style.display = 'none';
        wrap.style.display = 'block';
        wrap.focus();
      } catch (e) {
        showError(e.message);
        btnConnect.textContent = 'Подключиться';
      }
    });
  } else {
    baseUrl = ''; 
    currentClientId = 'local_' + Math.random().toString(36).substr(2, 9);
    if (wrap) wrap.style.display = 'block';
    
    startConnection().catch(e => {
        if (status) status.textContent = 'Ошибка: ' + e.message;
        console.error(e);
    });
  }

  if (btnDisconnect) {
    btnDisconnect.addEventListener('click', () => disconnect());
  }

  function disconnect() {
    if (iceInterval) clearInterval(iceInterval);
    if (dc) { dc.close(); dc = null; }
    if (pc) { pc.close(); pc = null; }

    if (baseUrl !== null && currentClientId) {
      fetch(`${baseUrl}/api/reset?clientId=${currentClientId}`, {
        method: 'POST',
        headers: authHeader ? { 'Authorization': authHeader } : {}
      }).catch(() => {});
    }

    video.srcObject = null;
    if (status) status.textContent = 'Отключено';
    log('');

    if (isRemoteMode) {
      wrap.style.display = 'none';
      loginScreen.style.display = 'flex';
      btnConnect.textContent = 'Подключиться';
    }
  }

  function showError(msg) {
    if (loginError) {
      loginError.textContent = msg;
      loginError.style.display = 'block';
    } else {
      console.error(msg);
    }
  }

  async function fetchAuth(url, options = {}) {
    if (authHeader) {
      if (!options.headers) options.headers = {};
      options.headers['Authorization'] = authHeader;
    }
    return fetch(url, options);
  }

  async function startConnection() {
    let stunPort = 3478;
    try {
      const cfgRes = await fetchAuth(`${baseUrl}/api/config`);
      if (cfgRes.status === 401) throw new Error('Ошибка авторизации');
      if (cfgRes.ok) {
        const cfg = await cfgRes.json();
        stunPort = cfg.stunPort;
      }
    } catch (e) {
      throw new Error('Не удалось связаться с устройством: ' + e.message);
    }

    const host = baseUrl ? new URL(baseUrl).hostname : window.location.hostname;
    const stunUrl = `stun:${host}:${stunPort}`;
    log(`STUN: ${stunUrl}`);

    pc = new RTCPeerConnection({
      iceServers: [{ urls: stunUrl }]
    });

    pc.ontrack = (e) => {
      if (e.streams && e.streams[0]) {
        video.srcObject = e.streams[0];
        video.muted = false;

        // Обработка блокировки автовоспроизведения со звуком
        let playPromise = video.play();
        if (playPromise !== undefined) {
            playPromise.catch(error => {
                console.log("Autoplay block: ", error);
                // Если браузер заблокировал, включаем без звука, чтобы было видео
                video.muted = true;
                video.play().catch(e => console.error("Muted autoplay failed", e));

                // Добавляем слушатели на первое взаимодействие для включения звука
                const unmute = () => {
                    video.muted = false;
                    video.play().catch(e => console.error(e));
                    document.removeEventListener('click', unmute);
                    document.removeEventListener('keydown', unmute);
                    document.removeEventListener('touchstart', unmute);
                };
                document.addEventListener('click', unmute);
                document.addEventListener('keydown', unmute);
                document.addEventListener('touchstart', unmute);
            });
        }

        if (status) status.textContent = 'Трансляция активна';
      }
    };

    pc.oniceconnectionstatechange = () => {
      if (status) status.textContent = 'ICE: ' + pc.iceConnectionState;
      if (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed') {
        disconnect();
      }
    };

    dc = pc.createDataChannel('control');
    dc.onopen = () => log('DataChannel открыт');
    dc.onclose = () => log('DataChannel закрыт');

    dc.onmessage = async (e) => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.t === 'download') {
          log('Скачивание: ' + msg.filename);
          const res = await fetchAuth(baseUrl + msg.url);
          if (res.ok) {
            const blob = await res.blob();
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = msg.filename;
            document.body.appendChild(a);
            a.click();
            setTimeout(() => {
              document.body.removeChild(a);
              URL.revokeObjectURL(a.href);
            }, 100);
          } else {
            log('Ошибка скачивания: ' + res.status);
          }
        }
      } catch (err) {
        console.error(err);
      }
    };

    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    const r = await fetchAuth(`${baseUrl}/api/offer?clientId=${currentClientId}`, {
      method: 'POST',
      body: offer.sdp,
      headers: { 'Content-Type': 'application/sdp' }
    });

    if (!r.ok) throw new Error('Ошибка сигналинга: ' + r.status);

    const answerSdp = await r.text();
    await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });

    iceInterval = setInterval(async () => {
      try {
        const rr = await fetchAuth(`${baseUrl}/api/ice?clientId=${currentClientId}`);
        if (!rr.ok) return;
        const arr = await rr.json();
        for (const c of arr) {
          try { await pc.addIceCandidate(c); } catch (e) {}
        }
      } catch (e) {}
    }, 1000);

    pc.onicecandidate = async (e) => {
      if (!e.candidate) return;
      try {
        await fetchAuth(`${baseUrl}/api/ice?clientId=${currentClientId}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sdpMid: e.candidate.sdpMid,
            sdpMLineIndex: e.candidate.sdpMLineIndex,
            candidate: e.candidate.candidate
          })
        });
      } catch (err) {}
    };
  }

  function getNormalizedCoords(e) {
    const rect = video.getBoundingClientRect();
    const vWidth = video.videoWidth;
    const vHeight = video.videoHeight;
    if (!vWidth || !vHeight) return null;

    const videoRatio = vWidth / vHeight;
    const elementRatio = rect.width / rect.height;
    let contentWidth, contentHeight, offsetX = 0, offsetY = 0;

    if (elementRatio > videoRatio) {
      contentHeight = rect.height;
      contentWidth = rect.height * videoRatio;
      offsetX = (rect.width - contentWidth) / 2;
    } else {
      contentWidth = rect.width;
      contentHeight = rect.width / videoRatio;
      offsetY = (rect.height - contentHeight) / 2;
    }

    const x = (e.clientX - rect.left - offsetX) / contentWidth;
    const y = (e.clientY - rect.top - offsetY) / contentHeight;

    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    return { x, y };
  }

  let pressTimer = null;
  let startCoords = null;
  let isSwiping = false;

  if (video) {
    video.addEventListener('mousedown', (e) => {
      e.preventDefault();
      if (e.button === 2) { sendControl({ t: 'back' }); return; }
      if (e.button === 0) {
        const coords = getNormalizedCoords(e);
        if (coords) {
          startCoords = coords;
          isSwiping = false;
          pressTimer = setTimeout(() => {
            if (!isSwiping && startCoords) {
              sendControl({ t: 'longpress', x: startCoords.x, y: startCoords.y, ms: 800 });
              startCoords = null;
            }
          }, 600);
        }
      }
    });

    video.addEventListener('mousemove', (e) => {
      e.preventDefault();
      if (!startCoords) return;
      const coords = getNormalizedCoords(e);
      if (!coords) return;
      const dx = coords.x - startCoords.x;
      const dy = coords.y - startCoords.y;
      if (Math.sqrt(dx*dx + dy*dy) > 0.03) {
        isSwiping = true;
        if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
      }
    });

    video.addEventListener('mouseup', (e) => {
      e.preventDefault();
      if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
      if (e.button === 0 && startCoords) {
        const endCoords = getNormalizedCoords(e);
        if (endCoords) {
          if (isSwiping) {
            sendControl({ t: 'swipe', x1: startCoords.x, y1: startCoords.y, x2: endCoords.x, y2: endCoords.y, ms: 300 });
          } else {
            sendControl({ t: 'tap', x: endCoords.x, y: endCoords.y });
          }
        }
        startCoords = null;
      }
    });

    let lastScrollTime = 0;
    video.addEventListener('wheel', (e) => {
      e.preventDefault();
      const now = Date.now();
      if (now - lastScrollTime < 80) return;
      lastScrollTime = now;
      const coords = getNormalizedCoords(e);
      if (coords) sendControl({ t: 'scroll', x: coords.x, y: coords.y, deltaY: e.deltaY });
    }, { passive: false });

    video.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  document.addEventListener('keydown', (e) => {
    if (['F5', 'F12', 'F11'].includes(e.key)) return;
    if (e.key === 'Backspace' || e.key === 'Enter' || e.key === 'Escape' || e.key === 'Tab') {
      e.preventDefault();
      sendControl({ t: 'key', key: e.key });
    }
  });

  document.addEventListener('keypress', (e) => {
    if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
      sendControl({ t: 'text', text: e.key });
    }
  });

  function sendControl(obj) {
    if (dc && dc.readyState === 'open') dc.send(JSON.stringify(obj));
  }

  if (dropZone) {
    window.addEventListener('dragenter', (e) => {
      e.preventDefault();
      if (wrap.style.display !== 'none') dropZone.style.display = 'flex';
    });
    window.addEventListener('dragover', (e) => e.preventDefault());
    window.addEventListener('dragleave', (e) => {
      e.preventDefault();
      if (e.relatedTarget === null || e.relatedTarget.id === 'wrap' || e.clientX === 0) dropZone.style.display = 'none';
    });
    window.addEventListener('drop', async (e) => {
      e.preventDefault();
      dropZone.style.display = 'none';
      const files = e.dataTransfer.files;
      if (files.length === 0) return;

      for (let i = 0; i < files.length; i++) {
        const file = files[i];
        if (status) status.textContent = `Загрузка: ${file.name}...`;
        try {
          const res = await fetchAuth(`${baseUrl}/api/upload?name=${encodeURIComponent(file.name)}`, {
            method: 'POST',
            body: file,
            headers: { 'Content-Type': 'application/octet-stream' }
          });
          if (res.ok) log(`Файл ${file.name} сохранен`);
          else log(`Ошибка загрузки ${file.name}: ${res.status}`);
        } catch (err) {
          log(`Сбой сети при загрузке ${file.name}`);
        }
      }
      if (status) status.textContent = 'Трансляция активна';
    });
  }
})();