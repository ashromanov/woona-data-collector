import unittest
from server.alignment import verified_alignment, sensor_intervals


class AlignmentTest(unittest.TestCase):
    def test_mapping_requires_bounded_error_and_exact_files(self):
        files=[{'artifact_type':k,'storage_status':'available','sha256':k} for k in ['packet','packet_timeline','video']]
        proof={'status':'verified','method':'motion_events','anchors':[{'video_seconds':v,'sensor_seconds':v+2,'uncertainty_seconds':0.1} for v in [0,10,20]], 'max_error_seconds':0.8,
               'offset_seconds':-2,'scale':1.001,**{k+'_sha256':k for k in ['packet','packet_timeline','video']}}
        self.assertIsNone(verified_alignment({'overall_sync_quality':'arrival_aligned'},files))
        self.assertEqual(verified_alignment({'alignment':proof},files),proof)
        span={'start_seconds':1,'end_seconds_exclusive':2}
        mapped=sensor_intervals(span,proof)[0]
        self.assertAlmostEqual(mapped['start_seconds'],3/1.001)
        self.assertAlmostEqual(mapped['end_seconds_exclusive'],4/1.001)
        for key,value in [('max_error_seconds',1.01),('max_error_seconds',float('nan')),('packet_sha256','wrong'),('scale',0),('anchors',[{}])]:
            with self.subTest(key=key,value=value):
                changed={**proof,key:value}
                self.assertIsNone(verified_alignment({'alignment':changed},files))

    def test_pauses_split_mapping_without_cropping_the_annotation(self):
        files = [{'artifact_type': k, 'storage_status': 'available', 'sha256': k} for k in ['packet', 'packet_timeline', 'video']]
        proof = {'status': 'verified', 'method': 'motion_events', 'max_error_seconds': .5,
                 **{k + '_sha256': k for k in ['packet', 'packet_timeline', 'video']},
                 'pieces': [{'video_start_seconds': 0, 'video_end_seconds': 10, 'scale': 1, 'offset_seconds': 0},
                            {'video_start_seconds': 10.1, 'video_end_seconds': 20, 'scale': 1, 'offset_seconds': -13}],
                 'anchors': [{'video_seconds': 1, 'sensor_seconds': 1, 'uncertainty_seconds': .1},
                             {'video_seconds': 5, 'sensor_seconds': 5, 'uncertainty_seconds': .1},
                             {'video_seconds': 15, 'sensor_seconds': 28, 'uncertainty_seconds': .1}]}
        self.assertEqual(verified_alignment({'alignment': proof}, files), proof)
        span = {'start_seconds': 9, 'end_seconds_exclusive': 12}
        mapped = sensor_intervals(span, proof)
        self.assertEqual([(i['start_seconds'], i['end_seconds_exclusive']) for i in mapped], [(9, 10), (23.1, 25)])
        self.assertEqual(span, {'start_seconds': 9, 'end_seconds_exclusive': 12})
        self.assertEqual(sensor_intervals({'start_seconds': 10, 'end_seconds_exclusive': 10.1}, proof), [])
        self.assertEqual(sensor_intervals({'start_seconds': 21, 'end_seconds_exclusive': 22}, proof), [])
        proof['pieces'][1]['video_start_seconds'] = 9
        self.assertIsNone(verified_alignment({'alignment': proof}, files))
