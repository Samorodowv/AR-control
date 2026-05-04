# Small Face Detectability Design

## Goal

Improve detection of far or small faces in the face-recognition preview pipeline while keeping obvious tiny false positives filtered out.

## Root Cause

The current pipeline has two small-face gates:

- ML Kit face detection is configured with `.setMinFaceSize(0.15f)`.
- `FaceBoxFilter` rejects boxes below width, height, and area ratios.

On a 1280x720 preview, the current filter rejects boxes below roughly 70 px wide, 58 px high, or 0.6% of frame area. That makes distant faces disappear even when ML Kit could produce a usable box.

## Approach

Use moderate threshold tuning instead of multi-scale detection.

- Lower ML Kit minimum face size from `0.15f` to `0.08f`.
- Loosen `FaceBoxFilter` thresholds so a roughly 60x70 px face at 1280x720 is accepted.
- Keep 40x40 px boxes rejected to avoid returning to the one-frame false-positive problem.
- Keep aspect-ratio filtering unchanged.
- Keep the existing two-hit `FaceBoxStabilizer`, so small boxes still need consecutive overlapping detections before reaching the overlay or enrollment.

## Safety

This change improves recall but may increase false positives. The existing stabilizer and aspect-ratio checks remain the guardrails. If hardware testing shows false boxes, the next step should be configurable sensitivity rather than hard-coding aggressive thresholds.

## Testing

Unit tests should cover:

- A smaller far-face box is accepted.
- A tiny box remains rejected.
- Invalid and very wide or tall boxes remain rejected.

