# Face Identity Retention Design

## Goal

Reduce visible red/green to white blinking for remembered faces without making first-time face recognition less conservative.

The current behavior clears a stable identity immediately when a single accepted frame has no confident match. Since identity confirmation then requires two consecutive matches, one weak frame can produce a visible white gap before the same approved or banned color returns.

## User Behavior

- A remembered approved face should stay green through brief recognition uncertainty.
- A remembered banned face should stay red through brief recognition uncertainty.
- White still means the app has no currently trusted remembered identity.
- No-face and multiple-face states should clear the retained identity immediately.
- A different remembered face must still be confirmed by repeated matching before the color changes.

## Approach

Use a small SORT-like retention policy on top of the existing tracking pieces instead of adding a full DeepSORT dependency.

The app already has:

- IoU-based face-box tracking in `FaceBoxStabilizer`.
- Face appearance comparison through FaceNet embeddings in `FaceEmbeddingMatcher`.
- Identity confirmation through `FaceIdentityStabilizer`.

The new behavior keeps the last stable identity for up to 3 accepted single-face frames when embedding matching returns no confident identity. Retention is frame-count based, not wall-clock based, so it follows the actual face pipeline cadence. At the current 250 ms face interval, 3 frames is roughly 750 ms.

## Components

- `FaceIdentityStabilizer`: owns confirmed identity, candidate identity, and retained-identity miss count.
- `MlKitFaceRecognizer`: calls the stabilizer with a flag that distinguishes retainable single-face uncertainty from non-retainable no-face or multiple-face states.
- Existing overlay code remains unchanged: it still renders red, green, or white from `FaceBoundingBox.accessStatus`.

## Data Flow

1. ML Kit detects faces and `FaceBoxStabilizer` emits stable face boxes.
2. If there is no stable face or there are multiple faces, the recognizer clears retained identity immediately.
3. If there is exactly one stable face, the app computes an embedding and asks `FaceEmbeddingMatcher` for the best remembered identity.
4. If the matcher returns the current stable identity, the retained miss count resets.
5. If the matcher returns no confident match, `FaceIdentityStabilizer` returns the last stable identity until 3 consecutive retainable misses expire.
6. If the matcher returns a different remembered identity, that identity must still satisfy the existing consecutive-match requirement before the overlay color changes.

## Safety Rules

- Retention only applies while the post-detection state is exactly one stable face.
- No-face, multiple-face, and frame-processing failures clear the retained identity immediately.
- Retention does not lower the initial match threshold.
- Retention does not bypass the ambiguity margin for new identities.

## Testing

Unit tests should cover:

- One or more retainable null matches keep the last stable face.
- The retained identity expires after 3 consecutive retainable misses.
- A non-retainable null clears the stable identity immediately.
- A different matched identity still requires two consecutive matches.
- The recognizer maps no-face and multiple-face pre-embedding states to non-retainable clears.

