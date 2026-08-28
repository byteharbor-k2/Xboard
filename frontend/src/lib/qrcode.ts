/**
 * Minimal QR Code encoder for otpauth:// enrolment URIs.
 *
 * Scope is deliberately narrow: byte mode, error correction level M,
 * versions 1-10 (up to 213 bytes). That covers every otpauth URI this
 * platform emits while keeping the admin login path free of third-party
 * runtime code, because the encoded payload carries the TOTP seed.
 *
 * Follows ISO/IEC 18004. Structure mirrors the reference algorithm:
 * GF(256) Reed-Solomon over the 0x11D primitive polynomial, standard
 * zigzag codeword placement, and mask selection by the four penalty rules.
 */

const ECC_PER_BLOCK = [10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
const BLOCK_COUNT = [1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
const MAX_VERSION = 10;
const PAD_BYTES = [0xec, 0x11];

const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);

for (let index = 0, value = 1; index < 255; index += 1) {
  EXP[index] = value;
  LOG[value] = index;
  value <<= 1;
  if (value & 0x100) {
    value ^= 0x11d;
  }
}
for (let index = 255; index < 512; index += 1) {
  EXP[index] = EXP[index - 255];
}

function multiply(a: number, b: number): number {
  if (a === 0 || b === 0) {
    return 0;
  }
  return EXP[LOG[a] + LOG[b]];
}

function rawDataModules(version: number): number {
  let result = (16 * version + 128) * version + 64;
  if (version >= 2) {
    const alignCount = Math.floor(version / 7) + 2;
    result -= (25 * alignCount - 10) * alignCount - 55;
    if (version >= 7) {
      result -= 36;
    }
  }
  return result;
}

function totalCodewords(version: number): number {
  return Math.floor(rawDataModules(version) / 8);
}

function dataCodewords(version: number): number {
  return (
    totalCodewords(version) -
    ECC_PER_BLOCK[version - 1] * BLOCK_COUNT[version - 1]
  );
}

function characterCountBits(version: number): number {
  return version < 10 ? 8 : 16;
}

function byteCapacity(version: number): number {
  const headerBits = 4 + characterCountBits(version);
  return Math.floor((dataCodewords(version) * 8 - headerBits) / 8);
}

function alignmentPositions(version: number): number[] {
  if (version === 1) {
    return [];
  }
  const size = version * 4 + 17;
  const count = Math.floor(version / 7) + 2;
  const step = Math.ceil((version * 4 + 4) / (count * 2 - 2)) * 2;
  const result = [6];
  for (let position = size - 7; result.length < count; position -= step) {
    result.splice(1, 0, position);
  }
  return result;
}

/** BCH(18,6) for version info, and BCH(15,5) for format info. */
function bchRemainder(
  value: number,
  generator: number,
  degree: number
): number {
  let result = value << degree;
  const generatorBits = 32 - Math.clz32(generator);
  for (let bit = 32 - Math.clz32(result); bit >= generatorBits; bit -= 1) {
    if (result & (1 << (bit - 1))) {
      result ^= generator << (bit - generatorBits);
    }
  }
  return result;
}

function reedSolomonDivisor(degree: number): Uint8Array {
  const result = new Uint8Array(degree);
  result[degree - 1] = 1;
  let root = 1;
  for (let index = 0; index < degree; index += 1) {
    for (let position = 0; position < degree; position += 1) {
      result[position] = multiply(result[position], root);
      if (position + 1 < degree) {
        result[position] ^= result[position + 1];
      }
    }
    root = multiply(root, 0x02);
  }
  return result;
}

function reedSolomonRemainder(
  data: Uint8Array,
  divisor: Uint8Array
): Uint8Array {
  const result = new Uint8Array(divisor.length);
  for (const byte of data) {
    const factor = byte ^ result[0];
    result.copyWithin(0, 1);
    result[result.length - 1] = 0;
    for (let index = 0; index < divisor.length; index += 1) {
      result[index] ^= multiply(divisor[index], factor);
    }
  }
  return result;
}

class BitBuffer {
  private readonly bits: number[] = [];

  append(value: number, length: number): void {
    for (let index = length - 1; index >= 0; index -= 1) {
      this.bits.push((value >>> index) & 1);
    }
  }

  get length(): number {
    return this.bits.length;
  }

  toCodewords(count: number): Uint8Array {
    const result = new Uint8Array(count);
    for (let index = 0; index < this.bits.length; index += 1) {
      result[index >>> 3] |= this.bits[index] << (7 - (index & 7));
    }
    return result;
  }
}

function buildCodewords(bytes: Uint8Array, version: number): Uint8Array {
  const capacity = dataCodewords(version);
  const buffer = new BitBuffer();
  buffer.append(0b0100, 4);
  buffer.append(bytes.length, characterCountBits(version));
  for (const byte of bytes) {
    buffer.append(byte, 8);
  }

  const totalBits = capacity * 8;
  buffer.append(0, Math.min(4, totalBits - buffer.length));
  buffer.append(0, (8 - (buffer.length % 8)) % 8);

  const codewords = buffer.toCodewords(capacity);
  for (let index = buffer.length / 8, pad = 0; index < capacity; index += 1) {
    codewords[index] = PAD_BYTES[pad % 2];
    pad += 1;
  }
  return codewords;
}

/** Splits data into blocks, appends Reed-Solomon parity, and interleaves. */
function interleave(codewords: Uint8Array, version: number): Uint8Array {
  const blockCount = BLOCK_COUNT[version - 1];
  const eccLength = ECC_PER_BLOCK[version - 1];
  const total = totalCodewords(version);
  // Includes parity; short blocks hold one data codeword less than long ones.
  const shortBlockLength = Math.floor(total / blockCount);
  const shortBlockCount = blockCount - (total % blockCount);
  const divisor = reedSolomonDivisor(eccLength);

  const blocks: Uint8Array[] = [];
  for (let index = 0, offset = 0; index < blockCount; index += 1) {
    const dataLength =
      shortBlockLength - eccLength + (index < shortBlockCount ? 0 : 1);
    const data = codewords.subarray(offset, offset + dataLength);
    offset += dataLength;
    // Every block is padded to the long-block width with parity at the tail,
    // so a single column index addresses the same field in all blocks.
    const block = new Uint8Array(shortBlockLength + 1);
    block.set(data, 0);
    block.set(
      reedSolomonRemainder(data, divisor),
      block.length - eccLength
    );
    blocks.push(block);
  }

  const result = new Uint8Array(total);
  let cursor = 0;
  for (let position = 0; position < blocks[0].length; position += 1) {
    for (let index = 0; index < blockCount; index += 1) {
      // Short blocks have no codeword in the final data column.
      if (
        position === shortBlockLength - eccLength &&
        index < shortBlockCount
      ) {
        continue;
      }
      result[cursor] = blocks[index][position];
      cursor += 1;
    }
  }
  return result;
}

type Grid = {
  size: number;
  modules: boolean[][];
  reserved: boolean[][];
};

function createGrid(size: number): Grid {
  const modules: boolean[][] = [];
  const reserved: boolean[][] = [];
  for (let row = 0; row < size; row += 1) {
    modules.push(new Array<boolean>(size).fill(false));
    reserved.push(new Array<boolean>(size).fill(false));
  }
  return { size, modules, reserved };
}

function setFunctionModule(
  grid: Grid,
  x: number,
  y: number,
  dark: boolean
): void {
  grid.modules[y][x] = dark;
  grid.reserved[y][x] = true;
}

function drawFinderPattern(grid: Grid, centerX: number, centerY: number): void {
  for (let dy = -4; dy <= 4; dy += 1) {
    for (let dx = -4; dx <= 4; dx += 1) {
      const x = centerX + dx;
      const y = centerY + dy;
      if (x < 0 || x >= grid.size || y < 0 || y >= grid.size) {
        continue;
      }
      const distance = Math.max(Math.abs(dx), Math.abs(dy));
      setFunctionModule(grid, x, y, distance !== 2 && distance !== 4);
    }
  }
}

function drawAlignmentPattern(grid: Grid, centerX: number, centerY: number) {
  for (let dy = -2; dy <= 2; dy += 1) {
    for (let dx = -2; dx <= 2; dx += 1) {
      setFunctionModule(
        grid,
        centerX + dx,
        centerY + dy,
        Math.max(Math.abs(dx), Math.abs(dy)) !== 1
      );
    }
  }
}

function drawFunctionPatterns(grid: Grid, version: number): void {
  const size = grid.size;

  for (let index = 0; index < size; index += 1) {
    setFunctionModule(grid, 6, index, index % 2 === 0);
    setFunctionModule(grid, index, 6, index % 2 === 0);
  }

  drawFinderPattern(grid, 3, 3);
  drawFinderPattern(grid, size - 4, 3);
  drawFinderPattern(grid, 3, size - 4);

  const positions = alignmentPositions(version);
  for (let i = 0; i < positions.length; i += 1) {
    for (let j = 0; j < positions.length; j += 1) {
      const skipCorner =
        (i === 0 && j === 0) ||
        (i === 0 && j === positions.length - 1) ||
        (i === positions.length - 1 && j === 0);
      if (!skipCorner) {
        drawAlignmentPattern(grid, positions[i], positions[j]);
      }
    }
  }

  // Reserving the format area by drawing it keeps the timing modules at
  // (6,8) and (8,6) intact, which a blanket row/column fill would clobber.
  drawFormatInfo(grid, 0);
  setFunctionModule(grid, 8, size - 8, true);

  if (version >= 7) {
    const bits = (version << 12) | bchRemainder(version, 0x1f25, 12);
    for (let index = 0; index < 18; index += 1) {
      const dark = ((bits >>> index) & 1) !== 0;
      const a = size - 11 + (index % 3);
      const b = Math.floor(index / 3);
      setFunctionModule(grid, a, b, dark);
      setFunctionModule(grid, b, a, dark);
    }
  }
}

function drawFormatInfo(grid: Grid, mask: number): void {
  const size = grid.size;
  // 0b00 is the two-bit indicator for error correction level M.
  const data = (0b00 << 3) | mask;
  const bits = ((data << 10) | bchRemainder(data, 0x537, 10)) ^ 0x5412;

  for (let index = 0; index <= 5; index += 1) {
    setFunctionModule(grid, 8, index, ((bits >>> index) & 1) !== 0);
  }
  setFunctionModule(grid, 8, 7, ((bits >>> 6) & 1) !== 0);
  setFunctionModule(grid, 8, 8, ((bits >>> 7) & 1) !== 0);
  setFunctionModule(grid, 7, 8, ((bits >>> 8) & 1) !== 0);
  for (let index = 9; index < 15; index += 1) {
    setFunctionModule(grid, 14 - index, 8, ((bits >>> index) & 1) !== 0);
  }

  for (let index = 0; index < 8; index += 1) {
    setFunctionModule(
      grid,
      size - 1 - index,
      8,
      ((bits >>> index) & 1) !== 0
    );
  }
  for (let index = 8; index < 15; index += 1) {
    setFunctionModule(
      grid,
      8,
      size - 15 + index,
      ((bits >>> index) & 1) !== 0
    );
  }
}

function placeCodewords(grid: Grid, codewords: Uint8Array): void {
  const size = grid.size;
  let bit = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    // Column 6 is the vertical timing pattern: shift the pair left so the
    // remaining columns stay correctly paired all the way to the edge.
    if (right === 6) {
      right = 5;
    }
    for (let step = 0; step < size; step += 1) {
      for (let column = 0; column < 2; column += 1) {
        const x = right - column;
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? size - 1 - step : step;
        if (grid.reserved[y][x]) {
          continue;
        }
        if (bit < codewords.length * 8) {
          grid.modules[y][x] =
            ((codewords[bit >>> 3] >>> (7 - (bit & 7))) & 1) !== 0;
        }
        bit += 1;
      }
    }
  }
}

function maskBit(mask: number, x: number, y: number): boolean {
  switch (mask) {
    case 0:
      return (x + y) % 2 === 0;
    case 1:
      return y % 2 === 0;
    case 2:
      return x % 3 === 0;
    case 3:
      return (x + y) % 3 === 0;
    case 4:
      return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
    case 5:
      return ((x * y) % 2) + ((x * y) % 3) === 0;
    case 6:
      return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
    default:
      return ((((x + y) % 2) + ((x * y) % 3)) % 2) === 0;
  }
}

function applyMask(grid: Grid, mask: number): void {
  for (let y = 0; y < grid.size; y += 1) {
    for (let x = 0; x < grid.size; x += 1) {
      if (!grid.reserved[y][x] && maskBit(mask, x, y)) {
        grid.modules[y][x] = !grid.modules[y][x];
      }
    }
  }
}

function runPenalty(run: number): number {
  return run >= 5 ? 3 + (run - 5) : 0;
}

function penalty(grid: Grid): number {
  const size = grid.size;
  const modules = grid.modules;
  let score = 0;

  // Rule 1: runs of five or more same-coloured modules in a line.
  for (let y = 0; y < size; y += 1) {
    let run = 1;
    for (let x = 1; x < size; x += 1) {
      if (modules[y][x] === modules[y][x - 1]) {
        run += 1;
      } else {
        score += runPenalty(run);
        run = 1;
      }
    }
    score += runPenalty(run);
  }
  for (let x = 0; x < size; x += 1) {
    let run = 1;
    for (let y = 1; y < size; y += 1) {
      if (modules[y][x] === modules[y - 1][x]) {
        run += 1;
      } else {
        score += runPenalty(run);
        run = 1;
      }
    }
    score += runPenalty(run);
  }

  // Rule 2: 2x2 blocks of one colour.
  for (let y = 0; y < size - 1; y += 1) {
    for (let x = 0; x < size - 1; x += 1) {
      const value = modules[y][x];
      if (
        value === modules[y][x + 1] &&
        value === modules[y + 1][x] &&
        value === modules[y + 1][x + 1]
      ) {
        score += 3;
      }
    }
  }

  // Rule 3: finder-like 1:1:3:1:1 patterns with four light modules beside.
  const pattern = [true, false, true, true, true, false, true];
  const matchesAt = (
    read: (offset: number) => boolean | null,
    start: number
  ): boolean => {
    for (let index = 0; index < 7; index += 1) {
      if (read(start + index) !== pattern[index]) {
        return false;
      }
    }
    // Only fully in-symbol 11-module windows count, matching the common
    // reading of the rule; modules outside the symbol are not "light".
    const before = [-4, -3, -2, -1].every(
      (offset) => read(start + offset) === false
    );
    const after = [7, 8, 9, 10].every(
      (offset) => read(start + offset) === false
    );
    return before || after;
  };
  for (let y = 0; y < size; y += 1) {
    const read = (offset: number) =>
      offset < 0 || offset >= size ? null : modules[y][offset];
    for (let x = 0; x <= size - 7; x += 1) {
      if (matchesAt(read, x)) {
        score += 40;
      }
    }
  }
  for (let x = 0; x < size; x += 1) {
    const read = (offset: number) =>
      offset < 0 || offset >= size ? null : modules[offset][x];
    for (let y = 0; y <= size - 7; y += 1) {
      if (matchesAt(read, y)) {
        score += 40;
      }
    }
  }

  // Rule 4: deviation of dark module ratio from 50%.
  let dark = 0;
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      if (modules[y][x]) {
        dark += 1;
      }
    }
  }
  const total = size * size;
  const deviation = Math.floor((Math.abs(dark * 20 - total * 10) * 10) / total);
  score += deviation * 10;

  return score;
}

export type QrCode = {
  size: number;
  version: number;
  modules: boolean[][];
};

export class QrCapacityError extends Error {}

/**
 * Encodes `text` as a QR Code symbol and returns its module matrix.
 * Throws {@link QrCapacityError} when the payload exceeds version 10.
 *
 * `forcedMask` pins the mask pattern instead of picking the lowest-penalty
 * one. It exists so the output can be compared against a reference encoder
 * mask by mask; production callers should leave it unset.
 */
export function encodeQrCode(text: string, forcedMask?: number): QrCode {
  const bytes = new TextEncoder().encode(text);

  let version = 0;
  for (let candidate = 1; candidate <= MAX_VERSION; candidate += 1) {
    if (bytes.length <= byteCapacity(candidate)) {
      version = candidate;
      break;
    }
  }
  if (version === 0) {
    throw new QrCapacityError(
      `payload of ${bytes.length} bytes exceeds QR version ${MAX_VERSION}`
    );
  }

  const codewords = interleave(buildCodewords(bytes, version), version);

  let best: Grid | null = null;
  let bestScore = Number.POSITIVE_INFINITY;
  for (let mask = 0; mask < 8; mask += 1) {
    if (forcedMask !== undefined && mask !== forcedMask) {
      continue;
    }
    const grid = createGrid(version * 4 + 17);
    drawFunctionPatterns(grid, version);
    placeCodewords(grid, codewords);
    drawFormatInfo(grid, mask);
    applyMask(grid, mask);
    const score = penalty(grid);
    if (score < bestScore) {
      bestScore = score;
      best = grid;
    }
  }

  const grid = best as Grid;
  return { size: grid.size, version, modules: grid.modules };
}

/**
 * Renders a QR Code as an SVG path string plus its viewBox extent, so the
 * caller can inline it without any raster or third-party dependency.
 */
export function qrCodeSvgPath(
  code: QrCode,
  quietZone = 4
): { path: string; extent: number } {
  const parts: string[] = [];
  for (let y = 0; y < code.size; y += 1) {
    for (let x = 0; x < code.size; x += 1) {
      if (code.modules[y][x]) {
        parts.push(`M${x + quietZone},${y + quietZone}h1v1h-1z`);
      }
    }
  }
  return { path: parts.join(""), extent: code.size + quietZone * 2 };
}
