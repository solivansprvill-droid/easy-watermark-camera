/* ============================================================
 * 水印相机 PWA — 核心逻辑
 * 相机(getUserMedia) + 定位(Geolocation/Nominatim) + Canvas水印合成
 * ============================================================ */
'use strict';

/* ---------- DOM ---------- */
const $ = (id) => document.getElementById(id);
const screens = { start: $('screen-start'), camera: $('screen-camera'), result: $('screen-result') };
const video = $('video');
const canvas = $('canvas');
const resultImg = $('result-img');
const wmPreview = $('wm-preview');
const chipLoc = $('chip-loc');
const locStatus = $('loc-status');
const toast = $('toast');
const fileInput = $('file-input');

/* ---------- 状态 ---------- */
const state = {
  stream: null,
  facingMode: 'environment',   // 默认后置
  lastBlob: null,              // 当前合成的结果 Blob
  loc: null,                   // { lat, lon, addr }
  locWatchId: null,
  captureTime: null,           // 拍摄时刻（保证水印时间定格）
};

/* ---------- 工具 ---------- */
const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
const pad = (n) => String(n).padStart(2, '0');

function fmtParts(d) {
  return {
    date: `${d.getFullYear()}年${pad(d.getMonth() + 1)}月${pad(d.getDate())}日`,
    week: WEEKDAYS[d.getDay()],
    time: `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`,
  };
}

function showToast(msg, ms = 2200) {
  toast.textContent = msg;
  toast.hidden = false;
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => { toast.hidden = true; }, ms);
}

function switchScreen(name) {
  Object.values(screens).forEach((s) => s.classList.remove('active'));
  screens[name].classList.add('active');
}

/* ============================================================
 * 实时水印预览（取景器 + 启动页时间）
 * ============================================================ */
function tickPreview() {
  const p = fmtParts(new Date());
  wmPreview.querySelector('.wm-date').textContent = `${p.date} ${p.week}`;
  wmPreview.querySelector('.wm-time').textContent = p.time;
  wmPreview.querySelector('.wm-addr').textContent =
    state.loc ? (state.loc.addr || `${state.loc.lat.toFixed(5)}, ${state.loc.lon.toFixed(5)}`) : '正在获取定位…';
}
setInterval(tickPreview, 1000);
tickPreview();

/* ============================================================
 * 定位：Geolocation + Nominatim 反向地理编码
 * ============================================================ */
async function reverseGeocode(lat, lon) {
  try {
    const url = `https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=18&accept-language=zh-CN`;
    const res = await fetch(url, { headers: { Accept: 'application/json' } });
    if (!res.ok) return null;
    const data = await res.json();
    return data.display_name || null;
  } catch {
    return null; // 离线/被墙时降级为坐标显示
  }
}

function setLocUI(text, ok = false) {
  chipLoc.textContent = `📍 ${text}`;
  if (locStatus) {
    locStatus.textContent = ok ? `定位已就绪 · ${text}` : text;
    locStatus.classList.toggle('on', ok);
  }
}

async function updateLocation() {
  if (!('geolocation' in navigator)) {
    setLocUI('设备不支持定位');
    return;
  }
  setLocUI('定位中…');
  navigator.geolocation.getCurrentPosition(
    async (pos) => {
      const { latitude: lat, longitude: lon } = pos.coords;
      state.loc = { lat, lon, addr: null };
      setLocUI(`${lat.toFixed(4)}, ${lon.toFixed(4)}`);
      const addr = await reverseGeocode(lat, lon);
      if (state.loc && state.loc.lat === lat && state.loc.lon === lon) {
        state.loc.addr = addr;
        if (addr) setLocUI(addr.split(',').slice(-3).join(',').trim() || addr, true);
        else setLocUI('已定位（网络解析地址失败，显示坐标）', true);
      }
    },
    (err) => {
      const map = {
        1: '定位被拒绝，请在浏览器设置中允许',
        2: '定位不可用',
        3: '定位超时',
      };
      setLocUI(map[err.code] || '定位失败');
    },
    { enableHighAccuracy: true, timeout: 12000, maximumAge: 30000 }
  );
}

/* ============================================================
 * 相机
 * ============================================================ */
async function startCamera(facing = state.facingMode) {
  stopCamera();
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: {
        facingMode: { ideal: facing },
        width: { ideal: 1920 },
        height: { ideal: 1080 },
      },
      audio: false,
    });
    state.stream = stream;
    state.facingMode = facing;
    video.srcObject = stream;
    await video.play().catch(() => {});
  } catch (err) {
    // getUserMedia 失败 → 降级为系统相机（<input capture>）
    showToast('无法直接调起摄像头，已切换为系统相机拍照');
    fileInput.click();
  }
}

function stopCamera() {
  if (state.stream) {
    state.stream.getTracks().forEach((t) => t.stop());
    state.stream = null;
  }
}

/* ============================================================
 * 水印合成（马克风格：底部半透明信息卡）
 * ============================================================ */
function drawWatermark(ctx, W, H) {
  const p = fmtParts(state.captureTime || new Date());
  const addrLine = state.loc
    ? (state.loc.addr || `${state.loc.lat.toFixed(5)}, ${state.loc.lon.toFixed(5)}`)
    : '定位信息未获取';

  // 尺寸随分辨率缩放（以 1080p 为基准）
  const k = W / 1080;
  const padX = 36 * k, padY = 26 * k;
  const fsDate = 30 * k, fsTime = 58 * k, fsAddr = 26 * k, fsCoord = 22 * k;
  const lineGap = 12 * k;

  ctx.save();
  ctx.font = `${fsDate}px "PingFang SC","Microsoft YaHei",sans-serif`;
  const dateText = `${p.date}  ${p.week}`;
  const wDate = ctx.measureText(dateText).width;

  ctx.font = `bold ${fsTime}px "DIN Alternate","Roboto Mono",monospace`;
  const wTime = ctx.measureText(p.time).width;

  ctx.font = `${fsAddr}px "PingFang SC","Microsoft YaHei",sans-serif`;
  let addrText = addrLine;
  const maxAddrW = W * 0.86 - padX * 2;
  while (ctx.measureText(addrText).width > maxAddrW && addrText.length > 4) {
    addrText = addrText.slice(0, -2) + '…';
  }
  const wAddr = ctx.measureText(addrText).width;

  const coordText = state.loc
    ? `${state.loc.lat.toFixed(5)}, ${state.loc.lon.toFixed(5)}  ·  WGS-84`
    : 'GPS 未启用';
  ctx.font = `${fsCoord}px "PingFang SC","Microsoft YaHei",sans-serif`;
  const wCoord = ctx.measureText(coordText).width;

  // 卡片尺寸
  const cardW = Math.max(wDate, wTime, wAddr, wCoord) + padX * 2;
  const cardH = fsDate + fsTime + fsAddr + fsCoord + lineGap * 3 + padY * 2;
  const cardX = padX;
  const cardY = H - cardH - padX;

  // 半透明黑卡 + 圆角
  const r = 18 * k;
  ctx.fillStyle = 'rgba(0, 0, 0, 0.55)';
  ctx.beginPath();
  ctx.roundRect(cardX, cardY, cardW, cardH, r);
  ctx.fill();

  // 左侧强调竖条
  ctx.fillStyle = 'rgba(47, 129, 247, 0.95)';
  ctx.beginPath();
  ctx.roundRect(cardX, cardY + 14 * k, 6 * k, cardH - 28 * k, 3 * k);
  ctx.fill();

  ctx.fillStyle = '#fff';
  ctx.shadowColor = 'rgba(0,0,0,.5)';
  ctx.shadowBlur = 4 * k;
  ctx.textBaseline = 'top';
  const tx = cardX + padX + 10 * k;
  let ty = cardY + padY;

  // 日期行
  ctx.font = `${fsDate}px "PingFang SC","Microsoft YaHei",sans-serif`;
  ctx.globalAlpha = 0.95;
  ctx.fillText(dateText, tx, ty);
  ty += fsDate + lineGap;

  // 时间行（大号加粗）
  ctx.font = `bold ${fsTime}px "DIN Alternate","Roboto Mono",monospace`;
  ctx.globalAlpha = 1;
  ctx.fillText(p.time, tx, ty);
  ty += fsTime + lineGap;

  // 分隔细线
  ctx.globalAlpha = 0.35;
  ctx.fillRect(tx, ty, cardW - padX * 2 - 10 * k, 1.5 * k);
  ty += lineGap + 8 * k;

  // 地址行
  ctx.globalAlpha = 0.95;
  ctx.font = `${fsAddr}px "PingFang SC","Microsoft YaHei",sans-serif`;
  ctx.fillText(addrText, tx, ty);
  ty += fsAddr + lineGap * 0.8;

  // 坐标行
  ctx.globalAlpha = 0.75;
  ctx.font = `${fsCoord}px "PingFang SC","Microsoft YaHei",sans-serif`;
  ctx.fillText(coordText, tx, ty);

  ctx.restore();
}

async function capture() {
  if (!video.videoWidth) {
    showToast('相机尚未就绪，请稍候');
    return;
  }
  state.captureTime = new Date();
  const W = video.videoWidth, H = video.videoHeight;
  canvas.width = W; canvas.height = H;
  const ctx = canvas.getContext('2d');
  ctx.drawImage(video, 0, 0, W, H);
  drawWatermark(ctx, W, H);

  state.lastBlob = await new Promise((res) => canvas.toBlob(res, 'image/jpeg', 0.92));
  if (!state.lastBlob) { showToast('照片生成失败，请重试'); return; }

  resultImg.src = URL.createObjectURL(state.lastBlob);
  stopCamera();          // 拍完暂停取景，省电且防止误拍
  switchScreen('result');
}

/* ---------- 保存 / 分享 ---------- */
function fileName() {
  const p = fmtParts(state.captureTime || new Date());
  return `WATERMARK_${p.date.replace(/\D/g, '')}_${p.time.replace(/:/g, '')}.jpg`;
}

async function savePhoto() {
  if (!state.lastBlob) return;
  const name = fileName();

  // 优先安卓原生分享面板（可直接存相册）
  if (navigator.canShare && navigator.canShare({ files: [new File([state.lastBlob], name, { type: 'image/jpeg' })] })) {
    try {
      await navigator.share({ files: [new File([state.lastBlob], name, { type: 'image/jpeg' })], title: '水印照片' });
      return;
    } catch (e) {
      if (e.name === 'AbortError') return; // 用户取消
    }
  }
  // 降级：触发下载
  const a = document.createElement('a');
  a.href = URL.createObjectURL(state.lastBlob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 3000);
  showToast('已保存到下载目录');
}

/* ---------- 相册导入（降级路径） ---------- */
fileInput.addEventListener('change', async () => {
  const file = fileInput.files[0];
  fileInput.value = '';
  if (!file) return;
  state.captureTime = new Date(); // 导入照片水印时间为导入时刻
  const img = new Image();
  img.onload = async () => {
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(img, 0, 0);
    drawWatermark(ctx, img.naturalWidth, img.naturalHeight);
    state.lastBlob = await new Promise((res) => canvas.toBlob(res, 'image/jpeg', 0.92));
    resultImg.src = URL.createObjectURL(state.lastBlob);
    stopCamera();
    switchScreen('result');
  };
  img.src = URL.createObjectURL(file);
});

/* ============================================================
 * 事件绑定
 * ============================================================ */
$('btn-start').addEventListener('click', async () => {
  await updateLocation();
  switchScreen('camera');
  startCamera();
});

$('btn-pick').addEventListener('click', () => fileInput.click());
$('btn-album').addEventListener('click', () => fileInput.click());

$('btn-shutter').addEventListener('click', capture);
$('btn-switch').addEventListener('click', () => {
  startCamera(state.facingMode === 'environment' ? 'user' : 'environment');
});

$('btn-close').addEventListener('click', () => { stopCamera(); switchScreen('start'); });
$('btn-back').addEventListener('click', () => { switchScreen('camera'); startCamera(); });
$('btn-retake').addEventListener('click', () => { switchScreen('camera'); startCamera(); });
$('btn-save').addEventListener('click', savePhoto);

// 页面隐藏时释放摄像头
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopCamera();
});

/* ============================================================
 * PWA Service Worker
 * ============================================================ */
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
