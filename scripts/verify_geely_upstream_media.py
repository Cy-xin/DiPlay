#!/usr/bin/env python3
"""Verify media reuse against a caller-supplied checkout of pinned upstream sources.

No downloads or execution of upstream code. --upstream contains media/ and airplay/.
"""
import argparse
import hashlib
import json
from pathlib import Path

PIN = '615b32ce0b40cd0b99ce94ba23a512001553684f'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--upstream', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[1] / 'geely-core/src/main/java/com/shilapi/xcertplay'
results = []
PINNED_SOURCE_SHA256 = {'airplay/AudioStream.kt': '005800e9d6fee4b6bceb4df10ad877ea36db89997f542fb4ee01197671989610',
 'airplay/ScreenStream.kt': 'e4220c81865b04a873593c869565fff38b30d246f8fae9f1b6f78ac85f50c20b',
 'media/AudioBufferProgress.kt': '7c5c9f03f96df3dbc561981333629275315e5e3790e0f4560fe5fb416da2c5ef',
 'media/MediaAudioBuffer.kt': 'dc2be9208cd0d0140a89f0515ecf8630f34f60f80a8246b93f5ef995dcf745ce',
 'media/VideoDecodeQueue.kt': 'f25405cf47d5bb872a37e904627fda4b9b6a11e7506f8599e0b72d63bd0b8a2c',
 'media/VideoBacklogRecovery.kt': 'f68dc4ad4c92a35c352bdbe1ccf685d9ba249036064dfafd1161e0c67827dbd1',
 'media/VideoSurfaceHandoff.kt': '5cfdd4886b11dc8b8fb46296798f98bea2573c2952cf7e015a31b0534b4b84cb',
 'media/MediaClockTrim.kt': '29ce3a4281b16787427b87140f10f83c252c6ea7ce7082fa2a5e9c5c8d5fe5fc',
 'media/AndroidMediaSink.kt': '48a0ab2a915800f662e944e674d11b79f1a6b90b8c392489f7fcea341957c008'}
for name, digest in PINNED_SOURCE_SHA256.items():
    if hashlib.sha256((a.upstream/name).read_bytes()).hexdigest() != digest:
        raise SystemExit("Pinned upstream source digest mismatch: " + name)

def normalized(text):
    return text.rstrip()

def check(name, original, local, adjustment='terminal whitespace only'):
    if normalized(original) != normalized(local):
        raise SystemExit('Upstream mismatch: ' + name)
    results.append(dict(component=name, adjustment=adjustment,
                        upstream_sha256=hashlib.sha256(original.encode()).hexdigest(), matches=True))

for name in ['airplay/AudioStream.kt', 'airplay/ScreenStream.kt',
             'media/AudioBufferProgress.kt', 'media/MediaAudioBuffer.kt',
             'media/VideoDecodeQueue.kt', 'media/VideoBacklogRecovery.kt', 'media/VideoSurfaceHandoff.kt']:
    original_file = (a.upstream/name).read_text()
    local_file = (root/name).read_text()
    if name == 'airplay/ScreenStream.kt':
        expanded = ('class ScreenStream(\n'
                    '    private val key: ByteArray,\n'
                    '    private val expectedPeer: InetAddress,\n'
                    '    private val onDiagnostic: (String) -> Unit = {},\n'
                    ') : Closeable {')
        compact = ('class ScreenStream(private val key: ByteArray, private val expectedPeer: InetAddress, '
                   'private val onDiagnostic: (String) -> Unit = {}) : Closeable {')
        assert expanded in original_file and compact in local_file
        check(name, original_file, local_file.replace(compact, expanded), 'constructor formatting and terminal whitespace only')
    else:
        check(name, original_file, local_file)
original = (a.upstream/'media/AndroidMediaSink.kt').read_text()
local = (root/'media/GeelyReceiverMediaSink.kt').read_text()
def section(text, start, end):
    return text[text.index(start):text.index(end, text.index(start))]
video = section(local, 'private class VideoDecoder(', 'internal object VideoRecoveryFrameAge')
for line in ['    private val onShown:(VideoDecoder,Surface)->Unit={_,_->},',
             '                            stats.onQueuedFrame(System.nanoTime()-job.receivedNs,backlog.pendingFrames)',
             '                    outputSurface?.let {onShown(this,it)}']:
    assert line in video, 'Missing observation hook: ' + line
    video = video.replace(line + '\n', '')
check('VideoDecoder', section(original, 'private class VideoDecoder(', 'internal object VideoRecoveryFrameAge'),
      video, 'three observation hooks excluded; no decode algorithm changes')
check('AudioRenderer.trimClock', section(original, '    private fun trimClock(', '    /** For the packets queued since'),
      section(local, '    private fun trimClock(', '    /** For the packets queued since'))
clock = (root/'media/MediaClockTrim.kt').read_text().replace(
    '    // Read-only diagnostics; clock control remains identical to upstream 615b32ce.\n'
    '    val targetLevelFrames get() = targetFrames\n'
    '    val calibrating get() = measuring != null\n\n', '')
check('MediaClockTrim', (a.upstream/'media/MediaClockTrim.kt').read_text(), clock, 'read-only telemetry getters excluded')
print(json.dumps(dict(upstream_commit=PIN, checks=results), indent=2))
