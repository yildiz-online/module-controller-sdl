/*
 *
 * This file is part of the Yildiz-Engine project, licenced under the MIT License  (MIT)
 *
 * Copyright (c) 2022 Grégory Van den Borre
 *
 * More infos available: https://engine.yildiz-games.be
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 *  portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 *  WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT  HOLDERS BE LIABLE FOR ANY CLAIM,
 *  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE  SOFTWARE.
 *
 *
 */

package be.yildizgames.module.controller.sdl;

import be.yildizgames.module.controller.Controller;
import be.yildizgames.module.controller.ControllerCurrentState;
import be.yildizgames.module.controller.ControllerEngine;
import be.yildizgames.module.controller.ControllerEngineStatusListener;
import be.yildizgames.module.controller.ControllerListener;
import be.yildizgames.module.controller.ControllerMapper;
import be.yildizgames.module.controller.ThreadRunner;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryAddress;
import java.lang.foreign.MemorySession;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Grégory Van den Borre
 */
public class SdlControllerEngine implements ControllerEngine {

    private final List<ControllerEngineStatusListener> listeners = new ArrayList<>();

    private final SdlController[] controllers = new SdlController[4];
    private final MethodHandle[] getControllerFunctions = new MethodHandle[4];
    private final MethodHandle[] isControllerConnectedFunction = new MethodHandle[4];

    private final int[] controllerState = new int[4];

    private final MethodHandle[] getControllerNameFunction = new MethodHandle[4];
    private final Path lib;
    private final Path sdl;

    private boolean running;

    private final System.Logger logger = System.getLogger(this.getClass().getName());

    public SdlControllerEngine(Path lib, Path sdl) {
        super();
        this.lib = lib;
        this.sdl = sdl;
        for (int i = 0; i < controllers.length; i++) {
            controllers[i] = new SdlController();
        }
    }

    void controllerEvent(int controller, int button, boolean state) {
        if (state) {
            controllers[controller].pressed(button);
        } else {
            controllers[controller].released(button);
        }
    }

    @Override
    public final ControllerEngine addListener(ControllerEngineStatusListener l) {
        this.listeners.add(l);
        return this;
    }

    @Override
    public final Controller getController1() {
        return controllers[0];
    }

    @Override
    public final Controller getController2() {
        return controllers[1];
    }

    @Override
    public final Controller getController3() {
        return controllers[2];
    }

    @Override
    public final Controller getController4() {
        return controllers[3];
    }

    @Override
    public void reopen() {
        //Does nothing
    }

    @Override
    public final void close() {
        running = false;
    }

    void controllerConnected(int controller, boolean state) {
        controllers[controller].connected = state;
    }

    @Override
    public final void run() {
        try (var session = MemorySession.openConfined()){
            SymbolLookup.libraryLookup(this.sdl, session);
            var library = SymbolLookup.libraryLookup(this.lib, session);
            var linker = Linker.nativeLinker();

            this.getControllerFunctions[0] = linker.downcallHandle(library.lookup("update").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.getControllerFunctions[1] = linker.downcallHandle(library.lookup("getController2").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.getControllerFunctions[2] = linker.downcallHandle(library.lookup("getController3").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.getControllerFunctions[3] = linker.downcallHandle(library.lookup("getController4").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.isControllerConnectedFunction[0] = linker.downcallHandle(library.lookup("isC1Plugged").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.isControllerConnectedFunction[1] = linker.downcallHandle(library.lookup("isC2Plugged").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.isControllerConnectedFunction[2] = linker.downcallHandle(library.lookup("isC3Plugged").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.isControllerConnectedFunction[3] = linker.downcallHandle(library.lookup("isC4Plugged").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.getControllerNameFunction[0] = linker.downcallHandle(library.lookup("getControllerName").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            linker.downcallHandle(library.lookup("initControls").orElseThrow(), FunctionDescriptor.ofVoid()).invokeExact();
            this.running = true;
            this.listeners.forEach(ControllerEngineStatusListener::started);
            while (this.running) {
                try {
                    handleController0();
                    handleController(1);
                    handleController(2);
                    handleController(3);
                    Thread.sleep(20);
                } catch (Throwable e) {
                    this.logger.log(System.Logger.Level.ERROR, e);
                }
            }
            Linker.nativeLinker().downcallHandle(library.lookup("terminateControls").orElseThrow(), FunctionDescriptor.ofVoid()).invokeExact();
            this.listeners.forEach(ControllerEngineStatusListener::closed);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private void handleController0() throws Throwable {
        if (!controllers[0].used) {
            return;
        }
        var c = (int) getControllerFunctions[0].invokeExact();
        var controllerPlugged = ((int) isControllerConnectedFunction[0].invokeExact()) > 0;
        if (controllerPlugged != controllers[0].connected) {
            controllers[0].connected = controllerPlugged;
            if (controllerPlugged) {
                controllers[0].model = getControllerName(0);
            }
            controllers[0].listeners.forEach(a -> {
                if (controllerPlugged) {
                    a.controllerConnected();
                } else {
                    a.controllerDisconnected();
                }
            });
        }
        if (controllerPlugged) {
            int previousState = this.controllerState[0];
            if (c != previousState) {
                int xor = c ^ previousState;
                int pressed = c & xor;
                int released = previousState & xor;
                for (int i = 0; i < 16; i++) {
                    if ((pressed & (1L << i)) != 0) {
                        controllers[0].pressed(i);
                    }
                    if ((released & (1L << i)) != 0) {
                        controllers[0].released(i);
                    }
                }
                this.controllerState[0] = c;
            }
        }
    }

    private void handleController(int index) throws Throwable {
        if (!controllers[index].used) {
            return;
        }
        var controllerPlugged = ((int) isControllerConnectedFunction[index].invokeExact()) > 0;
        if (controllerPlugged != controllers[index].connected) {
            controllers[index].connected = controllerPlugged;
            controllers[index].listeners.forEach(a -> {
                if (controllerPlugged) {
                    controllers[index].model = getControllerName(index);
                    a.controllerConnected();
                } else {
                    a.controllerDisconnected();
                }
            });
        }
        if (controllerPlugged) {
            int previousState = this.controllerState[index];
            var c = (int) getControllerFunctions[index].invokeExact();
            if (c != previousState) {
                int xor = c ^ previousState;
                int pressed = c & xor;
                int released = previousState & xor;
                for (int i = 0; i < 16; i++) {
                    if ((pressed & (1L << i)) != 0) {
                        controllers[index].pressed(i);
                    }
                    if ((released & (1L << i)) != 0) {
                        controllers[index].released(i);
                    }
                }
                this.controllerState[index] = c;
            }
        }
    }

    private String getControllerName(int player) {
        try {
            return ((MemoryAddress) this.getControllerNameFunction[0].invokeExact(player)).getUtf8String(0);
        } catch (Throwable e) {
            logger.log(System.Logger.Level.ERROR, "", e);
            return "Undefined";
        }
    }

    private static class SdlController implements Controller {

        private final System.Logger logger = System.getLogger(this.getClass().getName());

        private final List<ControllerListener> listeners = new ArrayList<>();

        private boolean connected;

        private boolean used;

        private String model = "Undefined";

        @Override
        public ControllerCurrentState getState() {
            return null;
        }

        @Override
        public void addListener(ControllerListener l) {
            this.listeners.add(l);
        }

        @Override
        public void use() {
            this.used = true;
        }

        @Override
        public void stop() {
            this.used = false;
        }

        @Override
        public void use(ThreadRunner runner) {
            this.used = true;
        }

        @Override
        public void map(ControllerMapper mapper) {

        }

        @Override
        public boolean isUsed() {
            return this.used;
        }

        @Override
        public boolean isConnected() {
            return this.connected;
        }

        @Override
        public String getModel() {
            return this.model;
        }


        private void pressed(int button) {
            switch (button) {
                case 0 -> this.listeners.forEach(ControllerListener::controllerPress1);
                case 1 -> this.listeners.forEach(ControllerListener::controllerPress2);
                case 2 -> this.listeners.forEach(ControllerListener::controllerPress3);
                case 3 -> this.listeners.forEach(ControllerListener::controllerPress4);
                case 9 -> this.listeners.forEach(ControllerListener::controllerPressL1);
                case 10 -> this.listeners.forEach(ControllerListener::controllerPressR1);
                case 4 -> this.listeners.forEach(ControllerListener::controllerPressSelect);
                case 6 -> this.listeners.forEach(ControllerListener::controllerPressStart);
                case 11 -> this.listeners.forEach(ControllerListener::controllerPressUp);
                case 14 -> this.listeners.forEach(ControllerListener::controllerPressRight);
                case 12 -> this.listeners.forEach(ControllerListener::controllerPressDown);
                case 13 -> this.listeners.forEach(ControllerListener::controllerPressLeft);
                default -> this.logger.log(System.Logger.Level.WARNING, "Unknown pressed " + button);
            }
        }

        private void released(int button) {
            switch (button) {
                case 0 -> this.listeners.forEach(ControllerListener::controllerRelease1);
                case 1 -> this.listeners.forEach(ControllerListener::controllerRelease2);
                case 2 -> this.listeners.forEach(ControllerListener::controllerRelease3);
                case 3 -> this.listeners.forEach(ControllerListener::controllerRelease4);
                case 9 -> this.listeners.forEach(ControllerListener::controllerReleaseL1);
                case 10 -> this.listeners.forEach(ControllerListener::controllerReleaseR1);
                case 4 -> this.listeners.forEach(ControllerListener::controllerReleaseSelect);
                case 6 -> this.listeners.forEach(ControllerListener::controllerReleaseStart);
                case 11 -> this.listeners.forEach(ControllerListener::controllerReleaseUp);
                case 14 -> this.listeners.forEach(ControllerListener::controllerReleaseRight);
                case 12 -> this.listeners.forEach(ControllerListener::controllerReleaseDown);
                case 13 -> this.listeners.forEach(ControllerListener::controllerReleaseLeft);
                default -> this.logger.log(System.Logger.Level.WARNING, "Unknown released " + button);
            }
        }

    }
}
