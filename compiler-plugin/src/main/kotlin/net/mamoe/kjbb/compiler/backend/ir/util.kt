@file:JvmName("JvmBlockingBridgeUtils")
@file:Suppress("unused") // for public API

package net.mamoe.kjbb.compiler.backend.ir

import net.mamoe.kjbb.JvmBlockingBridge
import net.mamoe.kjbb.compiler.backend.jvm.BlockingBridgeAnalyzeResult
import net.mamoe.kjbb.compiler.backend.jvm.GeneratedBlockingBridgeStubForResolution
import net.mamoe.kjbb.compiler.backend.jvm.isJvm8OrHigher
import org.jetbrains.kotlin.backend.common.ir.allOverridden
import org.jetbrains.kotlin.backend.common.ir.allParameters
import org.jetbrains.kotlin.backend.common.lower.parents
import org.jetbrains.kotlin.backend.jvm.codegen.psiElement
import org.jetbrains.kotlin.codegen.topLevelClassAsmType
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.effectiveVisibility
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull


val JVM_BLOCKING_BRIDGE_FQ_NAME = FqName(JvmBlockingBridge::class.qualifiedName!!)

@Suppress(
    "INVISIBLE_REFERENCE",
    "EXPERIMENTAL_MARKER_CAN_ONLY_BE_USED_AS_ANNOTATION_OR_ARGUMENT_IN_USE_EXPERIMENTAL",
    "DEPRECATION_ERROR"
)
val GENERATED_BLOCKING_BRIDGE_FQ_NAME = FqName(net.mamoe.kjbb.GeneratedBlockingBridge::class.qualifiedName!!)

val JVM_BLOCKING_BRIDGE_ASM_TYPE = JVM_BLOCKING_BRIDGE_FQ_NAME.topLevelClassAsmType()
val GENERATED_BLOCKING_BRIDGE_ASM_TYPE = GENERATED_BLOCKING_BRIDGE_FQ_NAME.topLevelClassAsmType()

/**
 * For annotation class
 */
fun IrClass.isJvmBlockingBridge(): Boolean =
    symbol.owner.fqNameWhenAvailable?.asString() == JVM_BLOCKING_BRIDGE_FQ_NAME.asString()

/**
 * Filter by annotation `@JvmBlockingBridge`
 */
fun FunctionDescriptor.isJvmBlockingBridge(): Boolean = annotations.hasAnnotation(JVM_BLOCKING_BRIDGE_FQ_NAME)

/**
 * Filter by annotation `@JvmBlockingBridge`
 */
fun IrFunction.isJvmBlockingBridge(): Boolean = annotations.hasAnnotation(JVM_BLOCKING_BRIDGE_FQ_NAME)

@OptIn(ObsoleteDescriptorBasedAPI::class)
fun IrFunction.isGeneratedBlockingBridgeStub(): Boolean =
    this.descriptor.getUserData(GeneratedBlockingBridgeStubForResolution) == true

/**
 * Check whether a function is allowed to generate bridges with.
 *
 * The functions must
 * - be `final` or `open`
 * - have parent [IrClass]
 */
@OptIn(ObsoleteDescriptorBasedAPI::class)
fun IrFunction.analyzeCapabilityForGeneratingBridges(): BlockingBridgeAnalyzeResult {
    var annotationFromContainingClass = false

    val jvmBlockingBridgeAnnotationIr =
        jvmBlockingBridgeAnnotation()
            ?: jvmBlockingBridgeAnnotationOnContainingClass().also { annotationFromContainingClass = true }
            ?: return BlockingBridgeAnalyzeResult.MissingAnnotationPsi


    val jvmBlockingBridgeAnnotation =
        jvmBlockingBridgeAnnotationIr.psiElement
            ?: psiElement
            ?: descriptor.findPsi()
            ?: return BlockingBridgeAnalyzeResult.MissingAnnotationPsi

    if (this !is IrSimpleFunction) return BlockingBridgeAnalyzeResult.Inapplicable(jvmBlockingBridgeAnnotation)

    fun impl(): BlockingBridgeAnalyzeResult {
        // fun must be suspend and applied to member function
        if (!isSuspend || name.isSpecial) {
            return BlockingBridgeAnalyzeResult.Inapplicable(jvmBlockingBridgeAnnotation)
        }

        if (isGeneratedBlockingBridgeStub()) {
            // @JvmBlockingBridge and @GeneratedBlockingBridge both present
            return BlockingBridgeAnalyzeResult.FromStub
        }

        if (!visibility.normalize().effectiveVisibility(descriptor, true).publicApi) {
            // effectively internal api
            return BlockingBridgeAnalyzeResult.RedundantForNonPublicDeclarations(jvmBlockingBridgeAnnotation)
        }

        val containingClass = parentClassOrNull
        if (containingClass?.isInline == true) {
            // inside inline class not supported
            return BlockingBridgeAnalyzeResult.InlineClassesNotSupported(jvmBlockingBridgeAnnotation,
                containingClass.descriptor)
        }

        allParameters.firstOrNull { it.type.isInlined() }?.let { param ->
            // inline class param not yet supported
            return BlockingBridgeAnalyzeResult.InlineClassesNotSupported(
                param.psiElement ?: jvmBlockingBridgeAnnotation, param.descriptor)
        }

        if (containingClass?.isInterface == true) { // null means top-level, which is also accepted
            if (module.platform?.isJvm8OrHigher() != true) {
                // inside interface and JVM under 8
                return BlockingBridgeAnalyzeResult.InterfaceNotSupported(jvmBlockingBridgeAnnotation)
            }
        }

        val overridden = this.findOverriddenDescriptorsHierarchically {
            it.analyzeCapabilityForGeneratingBridges().shouldGenerate
        }

        if (overridden != null) {
            // super function has @
            // generate only if this function has @, or implied from @ on class, which concluded as 'isReal'
            return BlockingBridgeAnalyzeResult.OverridesSuper(isUserDeclaredFunction())
        }

        // super function no @
        // this function may has @ or implied from
        return if (isUserDeclaredFunction()) {
            // explicit 'override' then generate for it.
            BlockingBridgeAnalyzeResult.Allowed
        } else {
            // implicit override by compiler, don't generate.
            BlockingBridgeAnalyzeResult.BridgeAnnotationFromContainingDeclaration(null)
        }
    }

    val result = impl()
    if (annotationFromContainingClass) {
        if (!result.diagnosticPassed) {
            return BlockingBridgeAnalyzeResult.BridgeAnnotationFromContainingDeclaration(result)
        }
    }
    return result
}

fun IrSimpleFunction.isUserDeclaredFunction(): Boolean {
    return originalFunction.psiElement != null
}

fun IrSimpleFunction.findOverriddenDescriptorsHierarchically(filter: (IrSimpleFunction) -> Boolean): IrSimpleFunction? {
    for (override in this.allOverridden(false)) {
        if (filter(override)) {
            return override
        }
        val find = override.findOverriddenDescriptorsHierarchically(filter)
        if (find != null) return find
    }
    return null
}

internal fun IrAnnotationContainer.jvmBlockingBridgeAnnotation(): IrConstructorCall? =
    annotations.findAnnotation(JVM_BLOCKING_BRIDGE_FQ_NAME)

fun IrFunction.jvmBlockingBridgeAnnotationOnContainingClass(): IrConstructorCall? {
    val containingClass = parent

    if (containingClass is IrAnnotationContainer) {
        val annotation = containingClass.annotations.findAnnotation(JVM_BLOCKING_BRIDGE_FQ_NAME)
        if (annotation != null) return annotation
    }

    if (containingClass is IrClass) {
        val file = containingClass.parents.firstIsInstanceOrNull<IrFile>()
        val annotation = file?.annotations?.findAnnotation(JVM_BLOCKING_BRIDGE_FQ_NAME)
        if (annotation != null) return annotation
    }

    return null
}

internal val IrFunction.isFinal get() = this is IrSimpleFunction && this.modality == Modality.FINAL
internal val IrFunction.isOpen get() = this is IrSimpleFunction && this.modality == Modality.OPEN
internal val IrFunction.isAbstract get() = this is IrSimpleFunction && this.modality == Modality.ABSTRACT
