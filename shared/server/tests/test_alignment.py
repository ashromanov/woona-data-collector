import unittest
from server.alignment import verified_alignment, sensor_interval


class AlignmentTest(unittest.TestCase):
    def test_mapping_requires_bounded_error_and_exact_files(self):
        files=[{'artifact_type':k,'storage_status':'available','sha256':k} for k in ['packet','packet_timeline','video']]
        proof={'status':'verified','method':'motion_events','anchors':[{'video_seconds':v,'sensor_seconds':v+2,'uncertainty_seconds':0.1} for v in [0,10,20]], 'max_error_seconds':0.8,
               'offset_seconds':-2,'scale':1.001,**{k+'_sha256':k for k in ['packet','packet_timeline','video']}}
        self.assertIsNone(verified_alignment({'overall_sync_quality':'arrival_aligned'},files))
        self.assertEqual(verified_alignment({'alignment':proof},files),proof)
        span={'start_seconds':1,'end_seconds_exclusive':2}
        mapped=sensor_interval(span,proof)
        self.assertAlmostEqual(mapped['start_seconds'],3/1.001)
        self.assertAlmostEqual(mapped['end_seconds_exclusive'],4/1.001)
        for key,value in [('max_error_seconds',1.01),('max_error_seconds',float('nan')),('packet_sha256','wrong'),('scale',0),('anchors',[{}])]:
            with self.subTest(key=key,value=value):
                changed={**proof,key:value}
                self.assertIsNone(verified_alignment({'alignment':changed},files))
