// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: Apache-2.0

package com.juancams.rviz2mini.compat;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * What zenoh-kotlin uses from java.lang.ref.Cleaner (which on Android only exists since API 33), built with
 * PhantomReference: {@link #create()}, {@link #register} and {@link Cleanable#clean()}. The build replaces
 * java.lang.ref.Cleaner with this class in the io.zenoh classes (ZenohCleanerRemap in app/build.gradle.kts).
 *
 * Like the original: each action runs only once, either when clean() is called or when the object is no longer reachable
 * (on its own thread).
 */
public final class Cleaner {

    /** A registered action. */
    public interface Cleanable {
        void clean();
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    /** Keeps the references alive until they are cleaned (otherwise the GC would collect the PhantomReference itself). */
    private final Set<Ref> pending = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private Cleaner() {
        Thread t = new Thread(this::loop, "zenoh-cleaner");
        t.setDaemon(true);
        t.start();
    }

    public static Cleaner create() {
        return new Cleaner();
    }

    public Cleanable register(Object obj, Runnable action) {
        if (obj == null || action == null) throw new NullPointerException();
        Ref r = new Ref(obj, queue, action);
        pending.add(r);
        return r;
    }

    private void loop() {
        while (true) {
            try {
                ((Ref) queue.remove()).clean();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // Like Java's Cleaner: an action that fails does not stop the others.
            }
        }
    }

    private final class Ref extends PhantomReference<Object> implements Cleanable {
        private Runnable action;

        Ref(Object referent, ReferenceQueue<Object> q, Runnable action) {
            super(referent, q);
            this.action = action;
        }

        @Override
        public void clean() {
            Runnable a;
            synchronized (this) {
                a = action;
                action = null;
            }
            if (a == null) return;
            pending.remove(this);
            clear();
            a.run();
        }
    }
}
