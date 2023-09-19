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

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * @author Grégory Van den Borre
 */
public class ControllerCallback {

    private static final int[] directionPressed = {100, 100, 100, 100};

    private static SdlControllerEngine engine;

    private ControllerCallback() {
        super();
    }

    static void init(SdlControllerEngine e, SymbolLookup symbols) {
        engine = e;
        try {
            var cbHandle = MethodHandles.lookup().findStatic(ControllerCallback.class, "callback", MethodType.methodType(void.class, int.class, int.class, int.class));
            var callback = Linker.nativeLinker().upcallStub(cbHandle, FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), SegmentAllocator.implicitAllocator());
            var registerCallback = Linker.nativeLinker().downcallHandle(symbols.lookup("registerCallback").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            ;
            registerCallback.invokeExact(callback.address());
        } catch (Throwable t) {
            System.getLogger(ControllerCallback.class.getName()).log(System.Logger.Level.ERROR, "Error in controller callback", t);
        }
    }

    public static void callback(int controller, int button, int state) {
        if (button == 100) {
            int previous = directionPressed[controller];
            directionPressed[controller] = button;
            if (previous != button) {
                engine.controllerEvent(controller, previous, false);
            }
        } else if (button > 100) {
            int previous = directionPressed[controller];
            if (previous != button) {
                directionPressed[controller] = button;
                if (previous != 100) {
                    engine.controllerEvent(controller, previous, false);
                }
                engine.controllerEvent(controller, button, true);
            }
        } else if (button == 50) {
            engine.controllerConnected(controller, state > 0);
        } else {
            engine.controllerEvent(controller, button, state > 0);
        }

    }
}
