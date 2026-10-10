"""Known synthetic IEC61937 fixtures verify capture integrity summaries offline."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec=importlib.util.spec_from_file_location('inspector',Path(__file__).with_name('inspect-firetv-capture.py'))
inspector=importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)
data=(inspector.ROOT/'android-a34/app/src/debug/assets/fixtures/tones-51.ac3').read_bytes()
payload=data[:inspector.iec.ac3_header(data)['frameBytes']]
burst=(b'\x72\xf8\x1f\x4e'+struct.pack('<HH',1,len(payload)*8)+inspector.iec.swap16(payload)).ljust(6144,b'\0')

class Tests(unittest.TestCase):
    def test_valid_stream(self):
        r=inspector.inspect(burst*3)
        self.assertTrue(r['continuousAc3Segment'])
        self.assertEqual(r['crcValidFrames'],3)
        self.assertEqual(r['channelsInCrcValidFrames'],{'6':3})
    def test_corruption(self):
        damaged=bytearray(burst);damaged[100]^=1
        r=inspector.inspect(burst+damaged+burst)
        self.assertFalse(r['continuousAc3Segment'])
        self.assertEqual(r['crcInvalidFrames'],1)
    def test_missing_carrier_bytes(self):
        r=inspector.inspect(burst+burst[192:]+burst)
        self.assertFalse(r['continuousAc3Segment'])
    def test_silence_not_audio_approval(self):
        r=inspector.inspect(bytes(6144))
        self.assertFalse(r['continuousAc3Segment'])
        self.assertEqual(r['crcValidFrames'],0)

if __name__=='__main__': unittest.main()
