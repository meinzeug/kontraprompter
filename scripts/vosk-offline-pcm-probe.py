#!/usr/bin/env python3
"""Read synthetic 48-kHz mono s16le PCM from stdin; no microphone or network."""
import ctypes
import hashlib
import json
import os
import sys

lib = ctypes.CDLL(os.environ['VOSK_FIXTURE_LIBRARY'])
lib.vosk_set_log_level.argtypes = [ctypes.c_int]
lib.vosk_set_log_level(-1)
lib.vosk_model_new.argtypes = [ctypes.c_char_p]
lib.vosk_model_new.restype = ctypes.c_void_p
lib.vosk_model_free.argtypes = [ctypes.c_void_p]
lib.vosk_recognizer_new.argtypes = [ctypes.c_void_p, ctypes.c_float]
lib.vosk_recognizer_new.restype = ctypes.c_void_p
lib.vosk_recognizer_free.argtypes = [ctypes.c_void_p]
lib.vosk_recognizer_accept_waveform.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
lib.vosk_recognizer_accept_waveform.restype = ctypes.c_int
lib.vosk_recognizer_result.argtypes = [ctypes.c_void_p]
lib.vosk_recognizer_result.restype = ctypes.c_char_p
lib.vosk_recognizer_final_result.argtypes = [ctypes.c_void_p]
lib.vosk_recognizer_final_result.restype = ctypes.c_char_p

model = lib.vosk_model_new(os.fsencode(os.environ['VOSK_FIXTURE_MODEL']))
if not model:
    raise RuntimeError('Testmodell konnte nicht geladen werden')
recognizer = None
try:
    recognizer = lib.vosk_recognizer_new(model, ctypes.c_float(48_000))
    if not recognizer:
        raise RuntimeError('Offline-Erkenner konnte nicht gestartet werden')
    digest = hashlib.sha256()
    count = 0
    pieces = []
    while chunk := sys.stdin.buffer.read(2048):
        if len(chunk) % 2:
            raise ValueError('PCM muss aus vollständigen 16-Bit-Samples bestehen')
        digest.update(chunk)
        count += len(chunk)
        result = lib.vosk_recognizer_accept_waveform(recognizer, chunk, len(chunk))
        if result < 0:
            raise RuntimeError('Offline-Erkennung fehlgeschlagen')
        if result:
            pieces.append(json.loads(lib.vosk_recognizer_result(recognizer))['text'])
    pieces.append(json.loads(lib.vosk_recognizer_final_result(recognizer))['text'])
    print(json.dumps({'pcmBytes': count, 'pcmSha256': digest.hexdigest(), 'text': ' '.join(p for p in pieces if p)}))
finally:
    if recognizer:
        lib.vosk_recognizer_free(recognizer)
    lib.vosk_model_free(model)
