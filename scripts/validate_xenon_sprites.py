#!/usr/bin/env python3
"""Offline sprite contract checker. No Android SDK or third-party packages required.

Usage: python3 scripts/validate_xenon_sprites.py
"""
from __future__ import annotations
import argparse, hashlib, json, re, struct, sys
from pathlib import Path


def png_info(path: Path) -> tuple[int,int,bool]:
    b=path.read_bytes()
    if not b.startswith(b'\x89PNG\r\n\x1a\n'):
        raise AssertionError(f'{path} is not a PNG')
    width,height,depth,color=struct.unpack('>IIBB',b[16:26])
    # An indexed image needs a tRNS chunk to encode transparent palette entries.
    has_alpha = color in (4,6) or (color==3 and b'tRNS' in b)
    return width,height,has_alpha


def check(root: Path, skip_kotlin=False) -> None:
    manifest_path=root/'assets/xenon-lifelike/manifest_v2.json'
    spec=json.loads(manifest_path.read_text(encoding='utf8'))
    maps=spec['actions'];sheets=spec['sheets']
    assert len(maps)==38, f'Expected 38 Action states, got {len(maps)}'
    for name,spec_sheet in sheets.items():
        path=root/spec_sheet['path']
        assert path.is_file(), f'Missing sprite atlas {path}'
        w,h,alpha=png_info(path)
        assert (w,h)==(spec_sheet['columns']*192,spec_sheet['rows']*208),(name,w,h)
        assert alpha, f'Atlas missing alpha transparency {path}'
        mirrored=root/'assets/xenon-lifelike'/path.name
        if mirrored.is_file():
            assert hashlib.sha256(path.read_bytes()).digest()==hashlib.sha256(mirrored.read_bytes()).digest(), f'Drifted mirror {mirrored}'
    # Locomotion V3 appends rows while keeping rows 0..3 stable for legacy actions.
    assert (sheets['MOTION']['columns'], sheets['MOTION']['rows']) == (8, 6), 'MOTION must be an 8x6 atlas'
    for action,row in [('RUN_RIGHT', 0), ('RUN_LEFT', 1), ('JUMP', 2), ('WALL_DESCENT', 3), ('WALK_FORWARD', 4), ('WALK_BACKWARD', 5)]:
        a=maps[action]
        assert a['sheet']=='MOTION' and a['row']==row, f'Locomotion row mismatch: {action}'
        assert len(a['frames'])==8, f'Locomotion cycle must contain eight frames: {action}'
    for action,m in maps.items():
        s=sheets[m['sheet']]
        assert 0<=m['row']<s['rows'], action
        assert m['frames'] and all(0<=x<s['columns'] for x in m['frames']),action
        assert 100<=m['frame_ms']<=1000,action
        assert isinstance(m['loops'],bool),action
    if not skip_kotlin:
        kt=(root/'app/src/main/java/com/xzero/agentephone/XenonSpriteView.kt').read_text(encoding='utf8')
        blocks=re.findall(r'^        ([A-Z_]+)\(\n([\s\S]*?)^        \)(?:,|$)',kt,re.MULTILINE)
        found={name for name,_ in blocks}
        assert found==maps.keys(), f'Kotlin/manifest drift: {found.symmetric_difference(maps.keys())}'
        for name,block in blocks:
            sheet=maps[name]['sheet']
            assert 'Sheet.'+sheet in block, f'{name} uses wrong sheet'
            assert 'Sheet.CORE' not in block and 'Sheet.EMOTIONS' not in block and 'Sheet.WALL_DESCENT' not in block, f'Legacy art still used by {name}'
            assert len(re.findall(r'Pair\(\d+,\d+\)',block))==len(maps[name]['frames']),f'Frame count mismatch {name}'
    print(f'OK: {len(maps)} actions; {len(sheets)} alpha PNG atlases; all indices in bounds')
    if skip_kotlin:
        print('NOTE: Kotlin-to-manifest check skipped in standalone art bundle')

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--root',default='.')
    p.add_argument('--skip-kotlin',action='store_true')
    a=p.parse_args()
    try:check(Path(a.root),a.skip_kotlin)
    except (AssertionError,KeyError,FileNotFoundError) as e:
        print(f'FAIL: {e}',file=sys.stderr)
        sys.exit(1)