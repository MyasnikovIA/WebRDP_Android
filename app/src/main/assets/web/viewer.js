(function () {
  const video = document.getElementById('video');
  const status = document.getElementById('status');
  const logEl = document.getElementById('log');
  const log = (s) => { logEl.textContent = s; };

  let pc, dc;

  async function start() {
    pc = new RTCPeerConnection({
      iceServers: [{ urls: 'stun:stun.l.google.com:19302' }]
    });

    pc.ontrack = (e) => {
      if (e.streams && e.streams[0]) {
        video.srcObject = e.streams[0];
        status.textContent = 'Трансляция активна';
      }
    };

    // Логируем ICE состояние для диагностики
    pc.oniceconnectionstatechange = () => {
      status.textContent = 'ICE: ' + pc.iceConnectionState;
    };

    dc = pc.createDataChannel('control');
    dc.onopen = () => log('DataChannel открыт (Управление доступно)');
    dc.onclose = () => log('DataChannel закрыт');

    pc.addTransceiver('video', { direction: 'recvonly' });

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    const r = await fetch('api/offer', {
      method: 'POST',
      body: offer.sdp,
      headers: { 'Content-Type': 'application/sdp' }
    });
    if (!r.ok) { status.textContent = 'Ошибка сигналинга: ' + r.status; return; }
    const answerSdp = await r.text();
    await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });

    setInterval(async () => {
      try {
        const rr = await fetch('api/ice');
        if (!rr.ok) return;
        const arr = await rr.json();
        for (const c of arr) {
          try { await pc.addIceCandidate(c); } catch (e) { /* ignore */ }
        }
      } catch (e) { /* ignore */ }
    }, 1000);

    pc.onicecandidate = async (e) => {
      if (!e.candidate) return;
      try {
        await fetch('api/ice', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sdpMid: e.candidate.sdpMid,
            sdpMLineIndex: e.candidate.sdpMLineIndex,
            candidate: e.candidate.candidate
          })
        });
      } catch (err) { /* ignore */ }
    };
  }

  // --- Расчет координат ---
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

  // --- Обработка мыши (mousedown / mouseup) ---
  let pressTimer = null;
  let startCoords = null;
  let isSwiping = false;

  video.addEventListener('mousedown', (e) => {
    // Правая кнопка мыши = "Назад" (Back)
    if (e.button === 2) {
      e.preventDefault();
      sendControl({ t: 'back' });
      return;
    }

    if (e.button === 0) { // Левая кнопка
      const coords = getNormalizedCoords(e);
      if (coords) {
        startCoords = coords;
        isSwiping = false;

        // Таймер для долгого нажатия (контекстное меню)
        pressTimer = setTimeout(() => {
          if (!isSwiping && startCoords) {
            sendControl({ t: 'longpress', x: startCoords.x, y: startCoords.y, ms: 800 });
            startCoords = null; // Сбрасываем, чтобы mouseup не отправил tap
          }
        }, 500); // 500мс - стандартный порог долгого нажатия Android
      }
    }
  });

  video.addEventListener('mousemove', (e) => {
    if (!startCoords) return;
    const coords = getNormalizedCoords(e);
    if (!coords) return;

    // Если сдвинулись достаточно далеко - отменяем долгое нажатие и начинаем свайп
    const dx = coords.x - startCoords.x;
    const dy = coords.y - startCoords.y;
    if (Math.sqrt(dx*dx + dy*dy) > 0.02) {
      isSwiping = true;
      if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }
    }
  });

  video.addEventListener('mouseup', (e) => {
    if (pressTimer) { clearTimeout(pressTimer); pressTimer = null; }

    if (e.button === 0 && startCoords) {
      const endCoords = getNormalizedCoords(e);
      if (endCoords) {
        if (isSwiping) {
          // Свайп
          sendControl({ t: 'swipe', x1: startCoords.x, y1: startCoords.y, x2: endCoords.x, y2: endCoords.y, ms: 300 });
        } else {
          // Обычный клик (если longpress не сработал)
          sendControl({ t: 'tap', x: endCoords.x, y: endCoords.y });
        }
      }
      startCoords = null;
    }
  });

  // Блокируем стандартное контекстное меню браузера
  video.addEventListener('contextmenu', (e) => e.preventDefault());

  // Обработка клавиатуры
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
    if (dc && dc.readyState === 'open') {
      dc.send(JSON.stringify(obj));
    }
  }

  start().catch((e) => { status.textContent = 'Ошибка: ' + e.message; console.error(e); });
})();