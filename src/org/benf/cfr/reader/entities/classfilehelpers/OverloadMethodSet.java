package org.benf.cfr.reader.entities.classfilehelpers;

import org.benf.cfr.reader.bytecode.analysis.parse.Expression;
import org.benf.cfr.reader.bytecode.analysis.parse.expression.Literal;
import org.benf.cfr.reader.bytecode.analysis.parse.literal.TypedLiteral;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;
import org.benf.cfr.reader.bytecode.analysis.types.*;
import org.benf.cfr.reader.entities.ClassFile;
import org.benf.cfr.reader.util.Functional;
import org.benf.cfr.reader.util.ListFactory;
import org.benf.cfr.reader.util.Predicate;
import org.benf.cfr.reader.util.SetFactory;
import org.benf.cfr.reader.util.functors.UnaryFunction;

import java.util.List;
import java.util.Set;

/**
 * Created with IntelliJ IDEA.
 * User: lee
 * Date: 25/07/2013
 * Time: 06:15
 * <p/>
 * These are the possibilities we could be hitting when we call an overloaded method.
 * We must be sure that parameter casting rewrites don't move a call from using one method to
 * using another.
 * <p/>
 * These are "vaguely" compatible - i.e. we shouldn't be comparing (int, int) with (Integer, String)
 * as an explicit cast could never call the wrong one.
 * <p/>
 * HOWEVER - we should be comparing a vararg method, as
 * <p/>
 * a,b (int int)
 * could be confused with
 * a,[]{b,c}
 */
public class OverloadMethodSet {
    private final ClassFile classFile;

    private static class MethodData {
        private final MethodPrototype methodPrototype;
        private final List<JavaTypeInstance> methodArgs;

        private MethodData(MethodPrototype methodPrototype, List<JavaTypeInstance> methodArgs) {
            this.methodPrototype = methodPrototype;
            this.methodArgs = methodArgs;
        }

        private JavaTypeInstance getArgType(int idx) {
            return methodArgs.get(idx);
        }

        public boolean is(MethodData other) {
            return methodPrototype == other.methodPrototype;
        }


        @Override
        public String toString() {
            return methodPrototype.toString();
        }

        private MethodData getBoundVersion(final GenericTypeBinder genericTypeBinder) {
            List<JavaTypeInstance> rebound = Functional.map(methodArgs, new UnaryFunction<JavaTypeInstance, JavaTypeInstance>() {
                @Override
                public JavaTypeInstance invoke(JavaTypeInstance arg) {
                    if (arg instanceof JavaGenericBaseInstance) {
                        return ((JavaGenericBaseInstance) arg).getBoundInstance(genericTypeBinder);
                    } else {
                        return arg;
                    }
                }
            });

            return new MethodData(methodPrototype, rebound);
        }
    }

    private final MethodData actualPrototype;
    private final List<MethodData> allPrototypes;

    public OverloadMethodSet(ClassFile classFile, MethodPrototype actualPrototype, List<MethodPrototype> allPrototypes) {
        this.classFile = classFile;
        UnaryFunction<MethodPrototype, MethodData> mk = new UnaryFunction<MethodPrototype, MethodData>() {
            @Override
            public MethodData invoke(MethodPrototype arg) {
                return new MethodData(arg, arg.getArgs());
            }
        };
        this.actualPrototype = mk.invoke(actualPrototype);
        this.allPrototypes = Functional.map(allPrototypes, mk);
    }

    private OverloadMethodSet(ClassFile classFile, MethodData actualPrototype, List<MethodData> allPrototypes) {
        this.classFile = classFile;
        this.actualPrototype = actualPrototype;
        this.allPrototypes = allPrototypes;
    }

    public OverloadMethodSet specialiseTo(JavaGenericRefTypeInstance type) {
        final GenericTypeBinder genericTypeBinder = classFile.getGenericTypeBinder(type);
        UnaryFunction<MethodData, MethodData> mk = new UnaryFunction<MethodData, MethodData>() {
            @Override
            public MethodData invoke(MethodData arg) {
                return arg.getBoundVersion(genericTypeBinder);
            }
        };
        return new OverloadMethodSet(classFile, mk.invoke(actualPrototype), Functional.map(allPrototypes, mk));
    }

    public JavaTypeInstance getArgType(int idx) {
        return actualPrototype.methodArgs.get(idx);
    }

    /*
     * Find which method this argument is MOST appropriate to.
     * If multiple, return false by definition.
     *
     * So if we have
     *
     * short arg
     *
     * the real method was expecting int.
     *
     * and there are methods expecting short, int, byte
     * then we're calling the wrong method, because short is an exact match.
     *
     * but if there are now methods expecting int, long.
     *
     *
     */
    public boolean callsCorrectMethod(Expression newArg, int idx) {
        JavaTypeInstance newArgType = newArg.getInferredJavaType().getJavaTypeInstance();

        /* First pass - find an exact match for the supplied arg - if the target method is ONE of the exact matches
         * for this arg, the we're no more wrong than we could have been... (!).
         */
        Set<MethodPrototype> exactMatches = SetFactory.newSet();
        for (MethodData prototype : allPrototypes) {
            JavaTypeInstance type = prototype.getArgType(idx);

            if (type.equals(newArgType)) {
                exactMatches.add(prototype.methodPrototype);
            }
        }
        // This is ok.
        if (exactMatches.contains(actualPrototype.methodPrototype)) return true;
        /*
         * Ok, we aren't a perfect match.  Find the set of arguments we COULD be cast to,
         * and sort according to closest match.
         * Iff there is ONE closest match, which is our target method, fine.
         *
         * We have to be aware of boxing here - (i.e.) Integer is a close match for int, but not perfect.
         * .. however integer is
         */
        JavaTypeInstance expectedArgType = actualPrototype.getArgType(idx);
        if (expectedArgType instanceof RawJavaType) {
            return callsCorrectApproxRawMethod(newArg, expectedArgType, newArgType, idx);
        } else {
            return callsCorrectApproxObjMethod(newArg, expectedArgType, newArgType, idx);
        }
    }

    public boolean callsCorrectApproxRawMethod(Expression newArg, JavaTypeInstance expected, JavaTypeInstance actual, int idx) {
        List<MethodData> matches = ListFactory.newList();
        for (MethodData prototype : allPrototypes) {
            JavaTypeInstance arg = prototype.getArgType(idx);
            // If it was equal, it would have been satisfied previously.
            if (actual.implicitlyCastsTo(arg) && actual.canCastTo(arg)) {
                matches.add(prototype);
            }
        }
        if (matches.isEmpty()) {
            // WTF?
            return false;
        }
        if (matches.size() == 1 && matches.get(0).is(actualPrototype)) {
            return true;
        }
        /*
         * Need to sort them according to how much type promotion is needed, we require our target
         * to be the first one.
         *
         * When ordering, if the actual type was an object, then we order boxed arguments before literals
         * otherwise we do it inverted.
         *
         * If succeeded,
         * to be truly accurate, this set has to be the set used to verify the type promotion for
         * subsequent arguments.
         */
        boolean boxingFirst = (!(actual instanceof RawJavaType));
        /*
         * We don't need to sort, we can just do a single run.
         */
        MethodData lowest = matches.get(0);
        JavaTypeInstance lowestType = lowest.getArgType(idx);
        for (int x = 1; x < matches.size(); ++x) {
            MethodData next = matches.get(x);
            JavaTypeInstance nextType = next.getArgType(idx);
            if (nextType.implicitlyCastsTo(lowestType)) {
                lowest = next;
                lowestType = nextType;
            }
        }

        if (lowest.is(actualPrototype)) return true;
        return false;
    }

    public boolean callsCorrectApproxObjMethod(Expression newArg, JavaTypeInstance expected, JavaTypeInstance actual, final int idx) {
        List<MethodData> matches = ListFactory.newList();
        boolean podMatchExists = false;
        boolean nonPodMatchExists = false;
        for (MethodData prototype : allPrototypes) {
            JavaTypeInstance arg = prototype.getArgType(idx);
            // If it was equal, it would have been satisfied previously.
            if (actual.implicitlyCastsTo(arg) && actual.canCastTo(arg)) {
                if (arg instanceof RawJavaType) {
                    podMatchExists = true;
                } else {
                    nonPodMatchExists = true;
                }
                matches.add(prototype);
            }
        }
        if (matches.isEmpty()) {
            // WTF?
            return false;
        }
        if (matches.size() == 1 && matches.get(0).is(actualPrototype)) {
            return true;
        }
        /* Special case - a literal null will cast to any ONE thing in preference to 'Object', but will
         * clash if there is more than one possibility.
         */
        Literal nullLit = new Literal(TypedLiteral.getNull());
        if (newArg.equals(nullLit) && actual == RawJavaType.NULL) {
            MethodData best = null;
            JavaTypeInstance bestType = null;
            for (MethodData match : matches) {
                JavaTypeInstance arg = match.getArgType(idx);
                if (!arg.equals(TypeConstants.OBJECT)) {
                    if (best == null) {
                        best = match;
                        bestType = arg;
                    } else {
                        if (arg.implicitlyCastsTo(bestType)) {
                            best = match;
                            bestType = arg;
                        } else if (bestType.implicitlyCastsTo(arg)) {
                            // We already had the better match.
                        } else {
                            // Type collision, needs cast.
                            return false;
                        }
                    }
                }
            }
            return (best.is(actualPrototype));
        }

        /*
         * If the argument is pod, then any valid pod path beats non pod
         * i.e
         *
         * x(short(y))
         *
         * will call x(int) rather than x(Short)
         */
        boolean isPOD = actual instanceof RawJavaType;
        boolean onlyMatchPod = isPOD && podMatchExists;

        /*
         * Ok, but if the argument isn't null......
         */
        if (onlyMatchPod) matches = Functional.filter(matches, new Predicate<MethodData>() {
            @Override
            public boolean test(MethodData in) {
                return (in.getArgType(idx) instanceof RawJavaType);
            }
        });
        if (!isPOD) {
            // Put object matches to the front.
            Pair<List<MethodData>, List<MethodData>> partition = Functional.partition(matches, new Predicate<MethodData>() {
                @Override
                public boolean test(MethodData in) {
                    return !(in.getArgType(idx) instanceof RawJavaType);
                }
            });
            matches.clear();
            matches.addAll(partition.getFirst());
            if (!nonPodMatchExists) matches.addAll(partition.getSecond());
        }

        if (matches.isEmpty()) return false;
        MethodData lowest = matches.get(0);
        JavaTypeInstance lowestType = lowest.getArgType(idx);
        for (int x = 0; x < matches.size(); ++x) {
            MethodData next = matches.get(x);
            JavaTypeInstance nextType = next.getArgType(idx);
            if (nextType.implicitlyCastsTo(lowestType)) {
                lowest = next;
                lowestType = nextType;
            }
        }

        if (lowest.is(actualPrototype)) return true;
        return false;
    }
}
