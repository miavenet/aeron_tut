"""Small fictional sequencing exercise. The wire format is defined in lesson 08."""
import struct
import zlib

HEADER = struct.Struct('<IHHIII')  # sequence, epoch, kind, origin, length, CRC32
PAYLOAD = struct.Struct('<II')     # request ID, value
MAX_PAYLOAD = 256


def encode(sequence, epoch, kind, origin, payload):
    if not 0 <= len(payload) <= MAX_PAYLOAD:
        raise ValueError('payload length')
    # TODO(L08-COMP1): keep epoch before kind in the wire header.
    prefix = struct.pack('<IHHII', sequence, kind, epoch, origin, len(payload))
    return prefix + struct.pack('<I', zlib.crc32(prefix + payload)) + payload


def fresh(epoch=7):
    return {'next': 1, 'epoch': epoch, 'version': 1, 'total': 0,
            'mutations': 0, 'results': {}, 'halted': False, 'last_result': None}


def apply(state, frame):
    if state['halted']:
        return 'HALTED'
    def halt(reason):
        state['halted'] = True
        return reason
    if len(frame) < HEADER.size:
        return halt('LENGTH')
    sequence, epoch, kind, origin, length, checksum = HEADER.unpack_from(frame)
    if length != PAYLOAD.size or length > MAX_PAYLOAD or len(frame) != HEADER.size + length:
        return halt('LENGTH')
    if zlib.crc32(frame[:16] + frame[20:]) != checksum:
        return halt('CHECKSUM')
    if epoch != state['epoch']:
        return halt('EPOCH')
    if sequence != state['next']:
        return halt('GAP' if sequence > state['next'] else 'DUP')
    request, value = PAYLOAD.unpack_from(frame, HEADER.size)
    if not request or kind not in (1, 2) or (kind == 2 and value not in (1, 2)):
        return halt('INPUT')
    # TODO(L08-R1): key by origin and request together.
    key = request
    prior = state['results'].get(key)
    if prior is not None:
        if prior[:2] != (kind, value):
            return halt('CONFLICT')
        state['next'] += 1
        state['last_result'] = prior[2]
        return 'CACHED'
    if kind == 2:
        state['version'] = value
    else:
        state['total'] += value * state['version']
        state['mutations'] += 1
    state['next'] += 1
    state['last_result'] = state['total']
    state['results'][key] = (kind, value, state['last_result'])
    return 'APPLIED'


def release(mode, recorded, writer_epoch, active_epoch):
    if writer_epoch != active_epoch or recorded[0] is None:
        return None
    if mode == 'local':
        return recorded[0]
    if mode == 'pair':
        return None if recorded[1] is None else max(recorded[:2])
    if mode == 'quorum':
        followers = [x for x in recorded[1:] if x is not None]
        return None if not followers else max(recorded[0], min(followers))
    raise ValueError('mode')


def metrics(samples, accepted, delivered):
    if accepted != delivered or delivered != len(samples) or not samples:
        raise ValueError('sample accounting')
    ordered = sorted(samples)
    n = len(ordered)
    median = (ordered[(n - 1) // 2] + ordered[n // 2]) / 2
    p99 = ordered[(99 * n + 99) // 100 - 1]
    return (n, median, p99, ordered[-1])
