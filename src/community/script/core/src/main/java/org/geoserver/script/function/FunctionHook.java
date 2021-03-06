/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.script.function;

import java.util.List;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.geoserver.script.ScriptHook;
import org.geoserver.script.ScriptPlugin;

/**
 * Hook for function objects.
 * <p>
 * This class is responsible for adapting a script into a function integrated into the geotools
 * filter function subsystem. This base implementation will look for a function named "run" defined
 * by the script, and invoke it with two arguments:
 * <ol>
 *   <li>The value the function is to be evaluated for</li>
 *   <li>A list of arguments derived from the parameters of the function</li>  
 * </ol>
 * Script plugins should extend this class to implement custom behaviour. 
 * See {@link ScriptPlugin#createFunctionHook()}
 * </p>
 * <p>
 * Instances of this class must be thread safe.
 * </p>
 * @author Justin Deoliveira, OpenGeo
 *
 */
public class FunctionHook extends ScriptHook {

    public FunctionHook(ScriptPlugin plugin) {
        super(plugin);
    }

    public Object run(Object value, List<Object> args, ScriptEngine engine) 
        throws ScriptException {

        return invoke(engine, "run", value, args);
    }
}
