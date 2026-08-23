package com.example.basilience;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

/**
 * One place that answers "is a growth cycle running right now?" for every
 * screen that needs to say so.
 *
 * <p>This deliberately adds no new query. It reuses the cycles listener that
 * Cycle Details and System Reports already use, and {@link CycleStatus} to
 * decide which document counts as active - so the app can never disagree with
 * itself about whether cultivation should be running.
 *
 * <p>The device firmware gates cultivation on the same underlying Firestore
 * state (mirrored to the device by an existing Cloud Function), so what these
 * screens report matches what the equipment is actually doing.
 *
 * <p>{@link State#LOADING} and {@link State#ERROR} are separate on purpose: a
 * screen must never flash "No Active Growth Cycle" while the first snapshot is
 * still in flight, and a failed read is not the same as an answer of "none".
 */
public final class CycleGateState {

    public enum State {
        /** No snapshot has arrived yet. Show nothing conclusive. */
        LOADING,
        /** A cycle is active - cultivation automation is running. */
        ACTIVE,
        /** No cycle is active. Cultivation is paused. */
        NONE,
        /** The cycles could not be read. Never treat this as NONE. */
        ERROR
    }

    /** Delivered on the main thread by the underlying Firestore listener. */
    public interface Listener {
        /**
         * @param state       current cycle state
         * @param hasAnyCycle whether the device has any cycle at all, active or
         *                    completed - lets a screen tell "never started" from
         *                    "the last cycle ended". Meaningless unless the
         *                    state is ACTIVE or NONE.
         */
        void onCycleStateChanged(@NonNull State state, boolean hasAnyCycle);
    }

    private CycleGateState() {}

    /**
     * Starts observing the device's cycles. The listener is called immediately
     * with {@link State#LOADING}, then on every change.
     *
     * @return the registration to remove in onDestroyView, or {@code null} when
     *         no device is selected (the listener is told ERROR in that case).
     */
    @Nullable
    public static ListenerRegistration observe(@NonNull Database_Helper helper,
                                               @Nullable String deviceId,
                                               @NonNull Listener listener) {
        listener.onCycleStateChanged(State.LOADING, false);

        if (deviceId == null || deviceId.isEmpty()) {
            listener.onCycleStateChanged(State.ERROR, false);
            return null;
        }

        helper.setSelectedDeviceId(deviceId);
        return helper.listenToCycles((snapshot, error) -> {
            if (error != null || snapshot == null) {
                listener.onCycleStateChanged(State.ERROR, false);
                return;
            }

            boolean hasAnyCycle = false;
            boolean active = false;

            for (DocumentSnapshot doc : snapshot.getDocuments()) {
                hasAnyCycle = true;
                if (CycleStatus.isActive(doc)) {
                    active = true;
                    break;
                }
            }

            listener.onCycleStateChanged(active ? State.ACTIVE : State.NONE, hasAnyCycle);
        });
    }

    /** Convenience for a screen that only needs the yes/no answer. */
    public static boolean hasActive(@Nullable QuerySnapshot cycles) {
        return CycleStatus.hasActive(cycles);
    }
}
