/*
 * This file is part of the Yildiz-Engine project, licenced under the MIT License  (MIT)
 *  Copyright (c) 2022-2023 Grégory Van den Borre
 *  More infos available: https://engine.yildiz-games.be
 *  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 *  documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 *  the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 *  permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright
 *  notice and this permission notice shall be included in all copies or substantial portions of the  Software.
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 *  WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 *  OR COPYRIGHT  HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 *  OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package be.yildizgames.module.controller.sdl;

import be.yildizgames.module.controller.Controller;
import be.yildizgames.module.controller.ControllerCurrentState;
import be.yildizgames.module.controller.ControllerEngine;
import be.yildizgames.module.controller.ControllerEngineStatusListener;
import be.yildizgames.module.controller.ControllerListener;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SDL implementation for the controller engine, before using this implementation it is necessary to load the native
 * libraries that are passed in the controller with System.load, sdl first, then libcontroller-sdl.
 * In Windows, the mingw runtime are also necessary before anything, first libgcc_s_seh-1.dll, then libstdc++-6.dll.
 *
 * @author Grégory Van den Borre
 */
public class SdlControllerEngine implements ControllerEngine {

    public static final int SDL_BUTTON_1 = 0;
    public static final int SDL_BUTTON_2 = 1;
    public static final int SDL_BUTTON_3 = 2;
    public static final int SDL_BUTTON_4 = 3;
    public static final int SDL_BUTTON_L1 = 9;
    public static final int SDL_BUTTON_R1 = 10;
    public static final int SDL_BUTTON_SELECT = 4;
    public static final int SDL_BUTTON_START = 6;
    public static final int SDL_BUTTON_L2 = 24;
    public static final int SDL_BUTTON_R2 = 25;
    public static final int SDL_DPAD_UP = 11;
    public static final int SDL_DPAD_RIGHT = 14;
    public static final int SDL_DPAD_DOWN = 12;
    public static final int SDL_DPAD_LEFT = 13;
    private final System.Logger logger = System.getLogger(this.getClass().getName());

    /**
     * Listeners that will be notified for every change in the engine status.
     */
    private final Collection<ControllerEngineStatusListener> engineStatusListeners = new ArrayList<>();

    private final Collection<ControllerListener> controllerListeners = new ArrayList<>();

    private final Map<Integer, SdlController> controllers = new HashMap<>();

    private MethodHandle updateControllerStatesFunction;

    private MethodHandle getControllerStateFunction;

    private MethodHandle getControllerNameFunction;

    private MethodHandle getControllerGuidFunction;

    private MethodHandle isControllerListChangedFunction;

    private MethodHandle getControllersFunction;

    private MethodHandle getControllerSizeFunction;

    private final Path lib;

    private final Path sdl;

    private boolean running;

    /**
     * Create an instance of the engine by providing the path of the necessary native libraries.
     * If the operating system is windows, it is expected to have loaded the mingw runtime libraries
     * before running the engine.
     * @param lib Path of the native library.
     * @param sdl Path of the SDL library.
     */
    public SdlControllerEngine(Path lib, Path sdl) {
        super();
        this.lib = lib;
        this.sdl = sdl;
    }

    public SdlControllerEngine() {
        var env_name = "NATIVE_CONTROLLER_PATH";
        var path = System.getProperty(env_name);
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + env_name + " is not set or empty, " +
                    "please provide the directory for SDL and libmodule-controller-sdl dynamic libraries.");
        }
        var libDirectory = Path.of(path).toAbsolutePath();
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            this.lib = libDirectory.resolve("libcontroller-sdl.dll");
            this.sdl = libDirectory.resolve("SDL2.dll");
        } else {
            this.lib = libDirectory.resolve("libcontroller-sdl.so");
            this.sdl = libDirectory.resolve("libsdl.so");
        }
        if (Files.notExists(this.lib)) {
            throw new IllegalArgumentException("File " + this.lib.toAbsolutePath() + " does not exists");
        }
    }

    @Override
    public final ControllerEngine addEngineStatusListener(ControllerEngineStatusListener l) {
        this.engineStatusListeners.add(l);
        return this;
    }

    @Override
    public final void addControllerListener(ControllerListener l) {
        this.controllerListeners.add(l);
    }

    @Override
    public final Collection<? extends Controller> getControllers() {
        return this.controllers.values();
    }

    @Override
    public void reopen() {
        //Does nothing
    }

    @Override
    public final void close() {
        running = false;
    }

    @Override
    public final void run() {
        try (var session = Arena.ofConfined()) {
            SymbolLookup.libraryLookup(this.sdl, session);
            var library = SymbolLookup.libraryLookup(this.lib, session);
            var linker = Linker.nativeLinker();

            this.updateControllerStatesFunction = linker.downcallHandle(library.find("update").orElseThrow(), FunctionDescriptor.ofVoid());
            this.getControllerStateFunction = linker.downcallHandle(library.find("getControllerState").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            this.getControllerNameFunction = linker.downcallHandle(library.find("getControllerName").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            this.getControllerGuidFunction = linker.downcallHandle(library.find("getControllerGuid").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            this.isControllerListChangedFunction = linker.downcallHandle(library.find("isControllerListChanged").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN));
            this.getControllerSizeFunction = linker.downcallHandle(library.find("getControllerNumber").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            this.getControllersFunction = linker.downcallHandle(library.find("getControllers").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
            linker.downcallHandle(library.find("initControls").orElseThrow(), FunctionDescriptor.ofVoid()).invokeExact();
            this.running = true;
            this.engineStatusListeners.forEach(ControllerEngineStatusListener::started);
            this.runLoop();
            Linker.nativeLinker().downcallHandle(library.find("terminateControls").orElseThrow(), FunctionDescriptor.ofVoid()).invokeExact();
            this.engineStatusListeners.forEach(ControllerEngineStatusListener::closed);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private void runLoop() {
        while (this.running) {
            try {
                this.updateControllerStatesFunction.invokeExact();
                var hasChanged = (boolean) this.isControllerListChangedFunction.invokeExact();
                if (hasChanged) {
                    var size = (int) this.getControllerSizeFunction.invokeExact();
                    var arrayPtr = (MemorySegment) this.getControllersFunction.invokeExact();
                    var ids = Arrays.stream(arrayPtr.reinterpret(ValueLayout.JAVA_INT.byteSize() * size).toArray(ValueLayout.JAVA_INT)).boxed().toList();
                    for (var id : ids) {
                        if (!this.controllers.containsKey(id)) {
                            var guid = getControllerGuid(id);
                            String name;
                            if("030044f05e040000e002000000007200".equals(guid)) {
                                name = "8BitDo Arcade Stick Switch";
                            } else {
                                name = getControllerName(id);
                            }
                            var controller = new SdlController(name, guid, id);
                            this.controllers.put(id, controller);
                            this.controllerListeners.forEach(l -> l.controllerConnected(controller));
                        }
                    }
                    var toRemove = new ArrayList<Integer>();
                    for(var connectedId : this.controllers.keySet()) {
                        if(!ids.contains(connectedId)) {
                            toRemove.add(connectedId);
                        }
                    }
                    List<Controller> removed = new ArrayList<>();
                    for(var itemToRemove : toRemove) {
                        removed.add(this.controllers.remove(itemToRemove));
                    }
                    for(var controller : removed) {
                        this.controllerListeners.forEach(l -> l.controllerDisconnected(controller));
                    }
                }
                this.controllers.keySet().forEach(this::handleController);
                Thread.sleep(20);
            } catch (Throwable e) {
                this.logger.log(System.Logger.Level.ERROR, "", e);
            }
        }
    }

    private void handleController(int controllerId) {
        try {
            var controller = this.controllers.get(controllerId);
            var newState = (int) this.getControllerStateFunction.invokeExact(controllerId);
            int previousState = controller.currentState.state;
            controller.currentState.state = newState;
            if (newState != previousState) {
                int xor = newState ^ previousState;
                int pressed = newState & xor;
                int released = previousState & xor;
                for (int i = 0; i < 32; i++) {
                    if ((pressed & (1L << i)) != 0) {
                        pressed(i, controller);
                    }
                    if ((released & (1L << i)) != 0) {
                        released(i,controller);
                    }
                }
            }
        } catch (Throwable e) {
            this.logger.log(System.Logger.Level.ERROR, "", e);
        }
    }

    private void pressed(int button, Controller controller) {
        switch (button) {
            case SDL_BUTTON_1 -> this.controllerListeners.forEach(l -> l.controllerPress1(controller));
            case SDL_BUTTON_2 -> this.controllerListeners.forEach(l -> l.controllerPress2(controller));
            case SDL_BUTTON_3 -> this.controllerListeners.forEach(l -> l.controllerPress3(controller));
            case SDL_BUTTON_4 -> this.controllerListeners.forEach(l -> l.controllerPress4(controller));
            case SDL_BUTTON_L1 -> this.controllerListeners.forEach(l -> l.controllerPressL1(controller));
            case SDL_BUTTON_R1 -> this.controllerListeners.forEach(l -> l.controllerPressR1(controller));
            case SDL_BUTTON_SELECT -> this.controllerListeners.forEach(l -> l.controllerPressSelect(controller));
            case SDL_BUTTON_START -> this.controllerListeners.forEach(l -> l.controllerPressStart(controller));
            case SDL_DPAD_UP -> this.controllerListeners.forEach(l -> l.controllerPressUp(controller));
            case SDL_DPAD_RIGHT -> this.controllerListeners.forEach(l -> l.controllerPressRight(controller));
            case SDL_DPAD_DOWN -> this.controllerListeners.forEach(l -> l.controllerPressDown(controller));
            case SDL_DPAD_LEFT -> this.controllerListeners.forEach(l -> l.controllerPressLeft(controller));
            case SDL_BUTTON_L2 -> this.controllerListeners.forEach(l -> l.controllerPressL2(controller));
            case SDL_BUTTON_R2 -> this.controllerListeners.forEach(l -> l.controllerPressR2(controller));
            default -> this.logger.log(System.Logger.Level.WARNING, "Unknown pressed " + button);
        }
    }

    private void released(int button, Controller controller) {
        switch (button) {
            case SDL_BUTTON_1 -> this.controllerListeners.forEach(l -> l.controllerRelease1(controller));
            case SDL_BUTTON_2 -> this.controllerListeners.forEach(l -> l.controllerRelease2(controller));
            case SDL_BUTTON_3 -> this.controllerListeners.forEach(l -> l.controllerRelease3(controller));
            case SDL_BUTTON_4 -> this.controllerListeners.forEach(l -> l.controllerRelease4(controller));
            case SDL_BUTTON_L1 -> this.controllerListeners.forEach(l -> l.controllerReleaseL1(controller));
            case SDL_BUTTON_R1 -> this.controllerListeners.forEach(l -> l.controllerReleaseR1(controller));
            case SDL_BUTTON_SELECT -> this.controllerListeners.forEach(l -> l.controllerReleaseSelect(controller));
            case SDL_BUTTON_START -> this.controllerListeners.forEach(l -> l.controllerReleaseStart(controller));
            case SDL_DPAD_UP -> this.controllerListeners.forEach(l -> l.controllerReleaseUp(controller));
            case SDL_DPAD_RIGHT -> this.controllerListeners.forEach(l -> l.controllerReleaseRight(controller));
            case SDL_DPAD_DOWN -> this.controllerListeners.forEach(l -> l.controllerReleaseDown(controller));
            case SDL_DPAD_LEFT -> this.controllerListeners.forEach(l -> l.controllerReleaseLeft(controller));
            case SDL_BUTTON_L2 -> this.controllerListeners.forEach(l -> l.controllerReleaseL2(controller));
            case SDL_BUTTON_R2 -> this.controllerListeners.forEach(l -> l.controllerReleaseR2(controller));
            default -> this.logger.log(System.Logger.Level.WARNING, "Unknown released " + button);
        }
    }

    private String getControllerName(int playerId) {
        try {
            return ((MemorySegment) this.getControllerNameFunction.invokeExact(playerId)).reinterpret(128).getString(0);
        } catch (Throwable e) {
            logger.log(System.Logger.Level.ERROR, "", e);
            return "Undefined";
        }
    }

    private String getControllerGuid(int playerId) {
        try {
            return ((MemorySegment) this.getControllerGuidFunction.invokeExact(playerId)).reinterpret(128).getString(0);
        } catch (Throwable e) {
            logger.log(System.Logger.Level.ERROR, "", e);
            return "Undefined";
        }
    }

    private static class SdlController implements Controller {

        private final String model;

        private final String guid;

        private final int id;

        private final SdlControllerCurrentState currentState = new SdlControllerCurrentState();

        private SdlController(String controllerName, String controllerGuid, int controllerId) {
            super();
            this.model = controllerName;
            this.guid = controllerGuid;
            this.id = controllerId;
        }

        @Override
        public String model() {
            return this.model;
        }

        @Override
        public String guid() {
            return this.guid;
        }

        @Override
        public int id() {
            return this.id;
        }

        @Override
        public ControllerCurrentState currentState() {
            return this.currentState;
        }
    }

    private static class SdlControllerCurrentState implements ControllerCurrentState {

        private int state = 0;

        @Override
        public boolean isButton1Pressed() {
            return (state & (1L << SDL_BUTTON_1)) > 0;
        }

        @Override
        public boolean isButton2Pressed() {
            return (state & (1L << SDL_BUTTON_2)) > 0;
        }

        @Override
        public boolean isButton3Pressed() {
            return (state & (1L << SDL_BUTTON_3)) > 0;
        }

        @Override
        public boolean isButton4Pressed() {
            return (state & (1L << SDL_BUTTON_4)) > 0;
        }

        @Override
        public boolean isButtonL1Pressed() {
            return (state & (1L << SDL_BUTTON_L1)) > 0;
        }

        @Override
        public boolean isButtonL2Pressed() {
            return (state & (1L << SDL_BUTTON_L2)) > 0;
        }

        @Override
        public boolean isButtonR1Pressed() {
            return (state & (1L << SDL_BUTTON_R1)) > 0;
        }

        @Override
        public boolean isButtonR2Pressed() {
            return (state & (1L << SDL_BUTTON_R2)) > 0;
        }

        @Override
        public boolean isButtonStartPressed() {
            return (state & (1L << SDL_BUTTON_START)) > 0;
        }

        @Override
        public boolean isButtonSelectPressed() {
            return (state & (1L << SDL_BUTTON_SELECT)) > 0;
        }

        @Override
        public boolean isPadUpPressed() {
            return (state & (1L << SDL_DPAD_UP)) > 0;
        }

        @Override
        public boolean isPadDownPressed() {
            return (state & (1L << SDL_DPAD_RIGHT)) > 0;
        }

        @Override
        public boolean isPadLeftPressed() {
            return (state & (1L << SDL_DPAD_DOWN)) > 0;
        }

        @Override
        public boolean isPadRightPressed() {
            return (state & (1L << SDL_DPAD_RIGHT)) > 0;
        }
    }
}
