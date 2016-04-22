//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import org.eclipse.jetty.websocket.common.util.DynamicArgs.Arg;
import org.eclipse.jetty.websocket.common.util.DynamicArgs.Signature;

public class UnorderedSignature implements Signature, BiPredicate<Method, Class<?>[]>
{
    private final Arg[] params;

    public UnorderedSignature(Arg... args)
    {
        this.params = args;
    }

    @Override
    public BiPredicate<Method, Class<?>[]> getPredicate()
    {
        return this;
    }

    @Override
    public boolean test(Method method, Class<?>[] types)
    {
        // Matches if the provided types
        // match the valid params in any order

        // Figure out mapping of calling args to method args
        Class<?> paramTypes[] = method.getParameterTypes();
        int paramTypesLength = paramTypes.length;

        // Method argument array pointing to index in calling array
        int callArgsLen = params.length;

        List<ArgIdentifier> argIdentifiers = DynamicArgs.lookupArgIdentifiers();

        for (int mi = 0; mi < paramTypesLength; mi++)
        {
            DynamicArgs.Arg methodArg = new DynamicArgs.Arg(method, mi, paramTypes[mi]);

            for (ArgIdentifier argId : argIdentifiers)
                methodArg = argId.apply(methodArg);

            int ref = -1;
            // Find reference to argument in callArgs
            for (int ci = 0; ci < callArgsLen; ci++)
            {
                if (methodArg.tag != null && methodArg.tag.equals(params[ci].tag))
                {
                    ref = ci;
                }
                else if (methodArg.type == params[ci].type)
                {
                    ref = ci;
                }
            }
            if (ref < 0)
            {
                return false;
            }
        }

        return true;
    }

    public void appendDescription(StringBuilder str)
    {
        str.append('(');
        boolean delim = false;
        for (Arg arg : params)
        {
            if (delim)
            {
                str.append(',');
            }
            str.append(' ');
            str.append(arg.type.getName());
            if (arg.type.isArray())
            {
                str.append("[]");
            }
            delim = true;
        }
        str.append(')');
    }

    @Override
    public BiFunction<Object, Object[], Object> getInvoker(Method method, Arg... callArgs)
    {
        int callArgsLen = callArgs.length;

        // Figure out mapping of calling args to method args
        Class<?> paramTypes[] = method.getParameterTypes();
        int paramTypesLength = paramTypes.length;

        // Method argument array pointing to index in calling array
        int argMapping[] = new int[paramTypesLength];
        int argMappingLength = argMapping.length;

        // ServiceLoader for argument identification plugins
        List<ArgIdentifier> argIdentifiers = DynamicArgs.lookupArgIdentifiers();
        DynamicArgs.Arg methodArgs[] = new DynamicArgs.Arg[paramTypesLength];
        for (int pi = 0; pi < paramTypesLength; pi++)
        {
            methodArgs[pi] = new DynamicArgs.Arg(method, pi, paramTypes[pi]);

            // Supplement method argument identification from plugins
            for (ArgIdentifier argId : argIdentifiers)
                methodArgs[pi] = argId.apply(methodArgs[pi]);
        }

        // Iterate through mappings, looking for a callArg that fits it
        for (int ai = 0; ai < argMappingLength; ai++)
        {
            int ref = -1;

            // Find reference to argument in callArgs
            for (int ci = 0; ci < callArgsLen; ci++)
            {
                if (methodArgs[ai].tag != null && methodArgs[ai].tag.equals(callArgs[ci].tag))
                {
                    ref = ci;
                    break;
                }
                else if (methodArgs[ai].index == callArgs[ci].index)
                {
                    ref = ci;
                    break;
                }
            }

            if (ref < 0)
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to map type [");
                err.append(methodArgs[ai].type);
                err.append("] in method ");
                ReflectUtils.append(err, method);
                err.append(" to calling args: (");
                boolean delim = false;
                for (Arg arg : callArgs)
                {
                    if (delim)
                        err.append(", ");
                    err.append(arg);
                    delim = true;
                }
                err.append(")");

                throw new DynamicArgsException(err.toString());
            }

            argMapping[ai] = ref;
        }

        // Return function capable of calling method
        return (obj, potentialArgs) -> {
            Object args[] = new Object[paramTypesLength];
            for (int i = 0; i < paramTypesLength; i++)
            {
                args[i] = potentialArgs[argMapping[i]];
            }
            try
            {
                return method.invoke(obj, args);
            }
            catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e)
            {
                StringBuilder err = new StringBuilder();
                err.append("Unable to call: ");
                ReflectUtils.append(err, obj.getClass(), method);
                err.append(" [with ");
                boolean delim = false;
                for (Object arg : args)
                {
                    if (delim)
                        err.append(", ");
                    if (arg == null)
                    {
                        err.append("<null>");
                    }
                    else
                    {
                        err.append(arg.getClass().getSimpleName());
                    }
                    delim = true;
                }
                err.append("]");
                throw new DynamicArgsException(err.toString(), e);
            }
        };
    }
}
