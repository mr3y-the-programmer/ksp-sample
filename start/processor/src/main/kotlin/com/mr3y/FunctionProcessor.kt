package com.mr3y

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.OutputStream

class FunctionProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation("com.mr3y.Function")
            .filterIsInstance<KSClassDeclaration>()
        if (!symbols.iterator().hasNext()) return emptyList()

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
            packageName = "com.mr3y",
            fileName = "GeneratedFunctions"
        )
        file += "package com.mr3y\n"

        symbols.forEach { it.accept(Visitor(file), Unit) }
        file.close()
        // unable to process those symbols, so, return them to be processed in another ksp round
        return symbols.filterNot { it.validate() }.toList()
    }

    operator fun OutputStream.plusAssign(str: String) {
        write(str.toByteArray())
    }

    inner class Visitor(private val file: OutputStream): KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (classDeclaration.classKind != ClassKind.INTERFACE) { // We only process interfaces
                logger.error("Only interface can be annotated with @Function", classDeclaration)
                return
            }
            val annotation = classDeclaration.annotations.first { it.shortName.asString() == "Function" }
            val nameArgument = annotation.arguments.first { arg -> arg.name?.asString() == "name" }
            val functionName = nameArgument.value as String

            val properties = classDeclaration.getAllProperties().filter { it.validate() }
            file += "\n"
            if (properties.iterator().hasNext()) {
                file += "fun $functionName(\n"
                properties.forEach { property ->
                    visitPropertyDeclaration(property, Unit)
                }
                file += ") {\n"
            } else {
                file += "fun $functionName() {\n"
            }

            // function body
            file += "   println(\"Hello From $functionName\")\n"
            file += "}\n"
        }

        override fun visitPropertyDeclaration(property: KSPropertyDeclaration, data: Unit) {
            val argumentName = property.simpleName.asString()
            file += "   $argumentName: "
            val resolvedType = property.type.resolve()
            file += resolvedType.declaration.qualifiedName?.asString() ?: run {
                logger.error("Invalid property type", property)
                return
            }

            val genericArguments = property.type.element?.typeArguments ?: emptyList()
            visitTypeArguments(genericArguments)

            file += if (resolvedType.nullability == Nullability.NULLABLE) "?" else ""
            file += ",\n"
        }

        private fun visitTypeArguments(typeArguments: List<KSTypeArgument>) {
            if (typeArguments.isNotEmpty()) {
                file += "<"
                typeArguments.forEachIndexed { index, arg ->
                    visitTypeArgument(arg, Unit)
                    if (index < typeArguments.lastIndex) file += ", "
                }
                file += ">"
            }
        }

        override fun visitTypeArgument(typeArgument: KSTypeArgument, data: Unit) {
            if (options["ignoreGenericArgs"] == "true") {
                file += "*"
                return
            }
            when(val variance = typeArgument.variance) {
                Variance.STAR -> {
                    file += "*"
                    return
                }
                Variance.COVARIANT, Variance.CONTRAVARIANT -> {
                    file += variance.label
                    file += " "
                }
                Variance.INVARIANT -> { /*no-op*/ }
            }
            val resolvedType = typeArgument.type?.resolve()
            file += resolvedType?.declaration?.qualifiedName?.asString() ?: run {
                logger.error("Invalid type argument", typeArgument)
                return
            }

            // Recursively, see if the type argument has in turn any type arguments
            val genericArguments = typeArgument.type?.element?.typeArguments ?: emptyList()
            visitTypeArguments(genericArguments)

            file += if (resolvedType?.nullability == Nullability.NULLABLE) "?" else ""
        }

    }
}