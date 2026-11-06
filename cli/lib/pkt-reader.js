'use strict';

/**
 * 零依赖的 .pkt 读取器：解析 zip 中央目录，取出 manifest.json 与图标。
 * 只支持 store / deflate 两种压缩方式（本工具链产出的包都是这两种）。
 */

const fs = require('fs');
const zlib = require('zlib');

const EOCD_SIG = 0x06054b50;
const CD_SIG = 0x02014b50;
const LOCAL_SIG = 0x04034b50;

function findEocd(buf) {
  const min = Math.max(0, buf.length - 22 - 65535);
  for (let i = buf.length - 22; i >= min; i--) {
    if (buf.readUInt32LE(i) === EOCD_SIG) return i;
  }
  return -1;
}

function readEntries(buf) {
  const eocd = findEocd(buf);
  if (eocd < 0) return [];
  const count = buf.readUInt16LE(eocd + 10);
  let offset = buf.readUInt32LE(eocd + 16);
  const entries = [];

  for (let i = 0; i < count; i++) {
    if (offset + 46 > buf.length || buf.readUInt32LE(offset) !== CD_SIG) break;
    const method = buf.readUInt16LE(offset + 10);
    const compSize = buf.readUInt32LE(offset + 20);
    const size = buf.readUInt32LE(offset + 24);
    const nameLen = buf.readUInt16LE(offset + 28);
    const extraLen = buf.readUInt16LE(offset + 30);
    const commentLen = buf.readUInt16LE(offset + 32);
    const localOffset = buf.readUInt32LE(offset + 42);
    const name = buf.toString('utf8', offset + 46, offset + 46 + nameLen);
    entries.push({ name, method, compSize, size, localOffset });
    offset += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function readEntryData(buf, entry) {
  const lh = entry.localOffset;
  if (lh + 30 > buf.length || buf.readUInt32LE(lh) !== LOCAL_SIG) return null;
  const nameLen = buf.readUInt16LE(lh + 26);
  const extraLen = buf.readUInt16LE(lh + 28);
  const start = lh + 30 + nameLen + extraLen;
  const raw = buf.slice(start, start + entry.compSize);
  if (entry.method === 0) return raw;
  if (entry.method === 8) return zlib.inflateRawSync(raw);
  return null;
}

const ICON_NAMES = ['icon.png', 'icon.webp', 'icon.jpg', 'icon.jpeg', 'icon.svg'];

function describePackage(file) {
  const buf = fs.readFileSync(file);
  const entries = readEntries(buf);
  if (!entries.length) return null;

  const manifestEntry = entries.find((e) => e.name === 'manifest.json');
  if (!manifestEntry) return null;

  let manifest;
  try {
    manifest = JSON.parse(readEntryData(buf, manifestEntry).toString('utf8'));
  } catch (e) {
    return null;
  }

  const iconEntry = (manifest.icon && entries.find((e) => e.name === manifest.icon)) ||
    entries.find((e) => ICON_NAMES.includes(e.name.toLowerCase()) && !e.name.includes('/'));

  return {
    manifest,
    iconName: iconEntry ? iconEntry.name : null,
    iconData: iconEntry ? readEntryData(buf, iconEntry) : null,
    size: buf.length
  };
}

module.exports = { describePackage };
