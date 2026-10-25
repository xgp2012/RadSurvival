// 最小 Minecraft Java 客户端（离线模式）：只走到 play 阶段并保持连接。
// 用途：在本机 Paper 测试服上获得一个真实的在线玩家，以便用 RCON 对其执行
// /rad force-check、/damage 等需要玩家实体的命令。
const net = require('net');
const crypto = require('crypto');
const zlib = require('zlib');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = Number(process.env.MC_PORT || 25565);
const NAME = process.env.MC_NAME || 'RadTest';

// ---------------------------------------------------------------- varint / 读写
function writeVarInt(value) {
  const out = [];
  let v = value >>> 0;
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    out.push(b);
  } while (v !== 0);
  return Buffer.from(out);
}

function readVarInt(buf, offset) {
  let value = 0, shift = 0, pos = offset;
  for (;;) {
    if (pos >= buf.length) return null;
    const b = buf[pos++];
    value |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) break;
    shift += 7;
    if (shift > 35) throw new Error('varint too long');
  }
  return { value: value | 0, size: pos - offset };
}

function stringField(s) {
  const b = Buffer.from(s, 'utf8');
  return Buffer.concat([writeVarInt(b.length), b]);
}

function packet(id, payload) {
  // 返回「包体」= varint(id) + payload（不含外层长度前缀；长度由 sendCompressed 统一加）
  return Buffer.concat([writeVarInt(id), payload]);
}

// ---------------------------------------------------------------- 连接状态
const sock = net.createConnection({ host: HOST, port: PORT });
let buffer = Buffer.alloc(0);
let state = 'handshake';
let threshold = -1; // 压缩阈值（-1 = 未启用）
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 19)}]`, ...a);

function send(buf) { sock.write(buf); }

// 压缩启用后：外层长度前缀 + (varint dataLength, 可能的 zlib 数据)
function sendCompressed(inner) {
  if (threshold < 0) {
    send(Buffer.concat([writeVarInt(inner.length), inner]));
    return;
  }
  if (inner.length < threshold) {
    const body = Buffer.concat([writeVarInt(0), inner]);
    send(Buffer.concat([writeVarInt(body.length), body]));
    return;
  }
  const compressed = zlib.deflateSync(inner);
  const body = Buffer.concat([writeVarInt(inner.length), compressed]);
  send(Buffer.concat([writeVarInt(body.length), body]));
}

sock.on('connect', () => {
  log('tcp connected');
  const host = stringField(HOST);
  const port = Buffer.alloc(2);
  port.writeUInt16BE(PORT);
  // handshake + login：压缩未启用，直接写（长度前缀由 sendCompressed 的 threshold<0 分支加）
  sendCompressed(packet(0x00, Buffer.concat([writeVarInt(774), host, port, writeVarInt(2)])));
  const uuid = crypto.createHash('md5').update(`OfflinePlayer:${NAME}`).digest();
  uuid[6] = (uuid[6] & 0x0f) | 0x30;
  uuid[8] = (uuid[8] & 0x3f) | 0x80;
  const hex = uuid.toString('hex').replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, '$1-$2-$3-$4-$5');
  // 1.21.11 ServerboundHelloPacket = name(utf, max16) + profileId(16 raw bytes)
  sendCompressed(packet(0x00, Buffer.concat([stringField(NAME), uuid])));
  state = 'login';
  log(`login start sent as ${NAME} (offline uuid ${hex})`);
});

sock.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  for (;;) {
    const lenInfo = readVarInt(buffer, 0);
    if (!lenInfo) return;
    if (buffer.length < lenInfo.size + lenInfo.value) return;
    let frame = buffer.subarray(lenInfo.size, lenInfo.size + lenInfo.value);
    buffer = buffer.subarray(lenInfo.size + lenInfo.value);
    if (threshold >= 0) {
      const dl = readVarInt(frame, 0);
      if (!dl) continue;
      const payload = frame.subarray(dl.size);
      frame = dl.value === 0 ? payload : zlib.inflateSync(payload);
    }
    const idInfo = readVarInt(frame, 0);
    if (!idInfo) continue;
    handlePacket(state, idInfo.value, frame.subarray(idInfo.size));
  }
});

function handlePacket(st, id, data) {
  if (st === 'login') {
    if (id === 0x03) { // set compression
      const t = readVarInt(data, 0);
      threshold = t.value;
      log('compression enabled, threshold =', threshold);
      return;
    }
    if (id === 0x02) { // login success → switch to configuration
      log('login success');
      state = 'config';
      // ClientInformation: language(utf) viewDistance(varint) chatVis(enum) chatColors(bool)
      //                    skinParts(byte) mainHand(enum) textFiltering(bool) allowListing(bool) particle(enum)
      const info = Buffer.concat([
        stringField('en_us'),
        writeVarInt(10),      // view distance
        writeVarInt(0),       // FULL chat
        Buffer.from([1]),     // chat colors
        Buffer.from([0x7f]),  // skin parts: all
        writeVarInt(1),       // main hand RIGHT
        Buffer.from([0]),     // text filtering off
        Buffer.from([1]),     // allow listing
        writeVarInt(0),       // particle ALL
      ]);
      sendCompressed(packet(0x00, info));
      return;
    }
    if (id === 0x00) {
      const d = readVarInt(data, 0);
      log('login disconnect:', data.subarray(d.size).toString('utf8'));
      process.exit(1);
    }
    return;
  }
  if (st === 'config') {
    // serverbound 配置阶段包 id（1.21.11 实序）：0=client info 1=cookie 2=custom payload
    // 3=finish configuration 4=keep alive 5=pong 6=resource pack 7=select known packs
    // 8=custom click 9=accept code of conduct
    if (id === 0x07) { // select known packs → 空列表
      sendCompressed(packet(0x07, writeVarInt(0)));
      return;
    }
    if (id === 0x09) { // code of conduct → 接受
      sendCompressed(packet(0x09, Buffer.alloc(0)));
      return;
    }
    if (id === 0x03) { // finish configuration
      sendCompressed(packet(0x03, Buffer.alloc(0)));
      state = 'play';
      log('PLAY phase reached');
      return;
    }
    if (id === 0x04) { // keep alive (configuration)
      sendCompressed(packet(0x04, data.subarray(0, 8)));
      return;
    }
    if (id === 0x00) { log('config disconnect'); process.exit(1); }
    return;
  }
  if (st === 'play') {
    if (id === 0x27 || id === 0x26 || id === 0x1d) { // keep alive (clientbound)
      sendCompressed(packet(0x1a, data.subarray(0, 8)));
      return;
    }
    if (id === 0x0a || id === 0x0b) { // plugin message / registry etc: ignore
      return;
    }
  }
}

sock.on('error', (e) => { log('socket error:', e.message); process.exit(1); });
sock.on('close', () => { log('socket closed'); process.exit(0); });
setTimeout(() => { log('still connected, staying alive'); }, 3000);
