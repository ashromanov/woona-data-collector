"""Validate hash-bound time mappings and preserve unmapped video intervals."""
import math


def finite(*values):
    return all(type(value) in (int, float) and math.isfinite(value) for value in values)


def mapping_parts(proof):
    if 'pieces' in proof:
        return proof['pieces']
    start, end = proof.get('video_overlap_seconds', [-math.inf, math.inf])
    return [{**proof, 'video_start_seconds': start, 'video_end_seconds': end}]


def verified_alignment(sync, artifacts):
    proof = sync.get('alignment')
    if not isinstance(proof, dict) or proof.get('status') != 'verified':
        return None
    error = proof.get('max_error_seconds')
    if not finite(error) or not 0 <= error <= 1:
        return None
    for kind in ['packet', 'packet_timeline', 'video']:
        file = next((a for a in artifacts if a['artifact_type'] == kind and a['storage_status'] == 'available'), None)
        if not file or proof.get(kind + '_sha256') != file['sha256']:
            return None
    coverage = proof.get('video_overlap_seconds')
    if coverage is not None and (not isinstance(coverage, list) or len(coverage) != 2 or not finite(*coverage) or coverage[0] >= coverage[1]):
        return None
    if 'pieces' in proof:
        pieces = proof['pieces']
        if not isinstance(pieces, list) or not pieces or proof.get('method') != 'motion_events':
            return None
        previous_end = -math.inf
        for piece in pieces:
            if not isinstance(piece, dict): return None
            start, end = piece.get('video_start_seconds'), piece.get('video_end_seconds')
            if not finite(start, end) or start >= end or start < previous_end:
                return None
            previous_end = end
    for piece in mapping_parts(proof):
        if not finite(piece.get('scale', 1), piece.get('offset_seconds')) or piece.get('scale', 1) <= 0:
            return None
    if proof.get('method') == 'motion_events':
        anchors = proof.get('anchors', [])
        if not isinstance(anchors, list) or len(anchors) < 3: return None
        for anchor in anchors:
            if not isinstance(anchor, dict): return None
            video, sensor, uncertainty = (anchor.get(key) for key in ['video_seconds', 'sensor_seconds', 'uncertainty_seconds'])
            if not finite(video, sensor, uncertainty) or uncertainty < 0: return None
            part = next((p for p in mapping_parts(proof) if p['video_start_seconds'] <= video < p['video_end_seconds']), None)
            if not part or abs(video - (part.get('scale', 1) * sensor + part['offset_seconds'])) + uncertainty > error:
                return None
        if max(a['sensor_seconds'] for a in anchors) <= min(a['sensor_seconds'] for a in anchors): return None
    elif proof.get('method') not in {'native_monotonic', 'shared_clock'} or not proof.get('clock_basis_confirmed') or not proof.get('latency_bound_measured'):
        return None
    return proof


def sensor_intervals(span, proof):
    intervals = []
    for part in mapping_parts(proof):
        start = max(span['start_seconds'], part['video_start_seconds'])
        end = min(span['end_seconds_exclusive'], part['video_end_seconds'])
        if start >= end: continue
        intervals.append({'start_seconds': (start - part['offset_seconds']) / part.get('scale', 1),
                          'end_seconds_exclusive': (end - part['offset_seconds']) / part.get('scale', 1),
                          'video_start_seconds': start, 'video_end_seconds_exclusive': end,
                          'time_basis': 'device_timer_since_first_packet', 'max_error_seconds': proof['max_error_seconds']})
    return intervals
