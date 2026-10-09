"""Validate a hash-bound time mapping; never infer certainty from an arrival flag."""
import math


def verified_alignment(sync, artifacts):
    proof = sync.get('alignment')
    if not proof or proof.get('status') != 'verified':
        return None
    error = proof.get('max_error_seconds')
    scale = proof.get('scale', 1)
    offset = proof.get('offset_seconds')
    if any(type(x) not in (int, float) or not math.isfinite(x) for x in [error,scale,offset]):
        return None
    if not 0 <= error <= 1 or scale <= 0:
        return None
    for kind in ['packet','packet_timeline','video']:
        file = next((a for a in artifacts if a['artifact_type']==kind and a['storage_status']=='available'),None)
        if not file or proof.get(kind+'_sha256')!=file['sha256']:
            return None
    if proof.get('method')=='motion_events':
        anchors=proof.get('anchors',[])
        if len(anchors)<3: return None
        for anchor in anchors:
            video=anchor.get('video_seconds');sensor=anchor.get('sensor_seconds');uncertainty=anchor.get('uncertainty_seconds')
            if any(type(x) not in (int,float) or not math.isfinite(x) for x in [video,sensor,uncertainty]): return None
            if uncertainty<0 or abs(video-(scale*sensor+offset))+uncertainty>error: return None
        if max(a['sensor_seconds'] for a in anchors)<=min(a['sensor_seconds'] for a in anchors): return None
    else:
        if not proof.get('clock_basis_confirmed') or not proof.get('latency_bound_measured'): return None
    if proof.get('method') not in {'motion_events','native_monotonic','shared_clock'}:
        return None
    return proof


def sensor_interval(span, proof):
    return {'start_seconds':(span['start_seconds']-proof['offset_seconds'])/proof.get('scale',1),
            'end_seconds_exclusive':(span['end_seconds_exclusive']-proof['offset_seconds'])/proof.get('scale',1),
            'time_basis':'device_timer_since_first_packet',
            'max_error_seconds':proof['max_error_seconds']}
