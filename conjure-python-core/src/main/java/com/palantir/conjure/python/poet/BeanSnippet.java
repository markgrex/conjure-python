/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.python.poet;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.palantir.conjure.python.processors.PythonIdentifierSanitizer;
import com.palantir.conjure.python.types.ImportTypeVisitor;
import com.palantir.conjure.spec.Documentation;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.immutables.value.Value;

@Value.Immutable
public interface BeanSnippet extends PythonSnippet {
    ImmutableList<PythonImport> DEFAULT_IMPORTS = ImmutableList.of(
            PythonImport.builder()
                    .moduleSpecifier(ImportTypeVisitor.CONJURE_PYTHON_CLIENT)
                    .addNamedImports(NamedImport.of("ConjureBeanType"), NamedImport.of("ConjureFieldDefinition"))
                    .build(),
            PythonImport.of("builtins"),
            PythonImport.builder()
                    .moduleSpecifier(ImportTypeVisitor.TYPING)
                    .addNamedImports(NamedImport.of("Dict"), NamedImport.of("List"))
                    .build());
    ImmutableSet<String> PROTECTED_FIELDS = ImmutableSet.of("fields");

    @Override
    @Value.Default
    default String idForSorting() {
        return className();
    }

    String className();

    String definitionName();

    PythonPackage definitionPackage();

    Optional<Documentation> docs();

    List<PythonField> fields();

    @Override
    default void emit(PythonPoetWriter poetWriter) {
        poetWriter.writeIndentedLine(String.format("class %s(ConjureBeanType):", className()));
        poetWriter.increaseIndent();
        docs().ifPresent(docs -> {
            poetWriter.writeIndentedLine("\"\"\"");
            poetWriter.writeIndentedLine(docs.get().trim());
            poetWriter.writeIndentedLine("\"\"\"");
        });

        poetWriter.writeLine();

        // record off the fields, for things like serialization (python... has no types)
        poetWriter.writeIndentedLine("@builtins.classmethod");
        poetWriter.writeIndentedLine("def _fields(cls) -> Dict[str, ConjureFieldDefinition]:");
        poetWriter.increaseIndent();
        poetWriter.writeIndentedLine("return {");
        poetWriter.increaseIndent();
        for (int i = 0; i < fields().size(); i++) {
            PythonField field = fields().get(i);
            poetWriter.writeIndentedLine(String.format(
                    "'%s': ConjureFieldDefinition('%s', %s)%s",
                    PythonIdentifierSanitizer.sanitize(field.attributeName()),
                    field.jsonIdentifier(),
                    field.pythonType(),
                    i == fields().size() - 1 ? "" : ","));
        }
        poetWriter.decreaseIndent();
        poetWriter.writeIndentedLine("}");
        poetWriter.decreaseIndent();

        poetWriter.writeLine();

        poetWriter.writeIndentedLine(String.format(
                "__slots__: List[str] = [%s]",
                fields().stream()
                        .map(field -> String.format(
                                "'_%s'", PythonIdentifierSanitizer.sanitize(field.attributeName(), PROTECTED_FIELDS)))
                        .collect(Collectors.joining(", "))));

        poetWriter.writeLine();

        // constructor -- only if there are fields
        if (!fields().isEmpty()) {
            poetWriter.writeIndentedLine(String.format(
                    "def __init__(self, %s) -> None:",
                    Joiner.on(", ")
                            .join(fields().stream()
                                    .sorted(new PythonField.PythonFieldComparator())
                                    .map(field -> {
                                        String name = String.format(
                                                "%s: %s",
                                                PythonIdentifierSanitizer.sanitize(field.attributeName()),
                                                field.myPyType());
                                        if (field.isOptional()) {
                                            return String.format("%s = None", name);
                                        }
                                        return name;
                                    })
                                    .collect(Collectors.toList()))));
            poetWriter.increaseIndent();
            fields().forEach(field -> poetWriter.writeIndentedLine(String.format(
                    "self._%s = %s",
                    PythonIdentifierSanitizer.sanitize(field.attributeName(), PROTECTED_FIELDS),
                    PythonIdentifierSanitizer.sanitize(field.attributeName()))));
            poetWriter.decreaseIndent();
        }

        // each property
        fields().forEach(field -> {
            poetWriter.writeLine();
            poetWriter.writeIndentedLine("@builtins.property");
            poetWriter.writeIndentedLine(String.format(
                    "def %s(self) -> %s:",
                    PythonIdentifierSanitizer.sanitize(field.attributeName()), field.myPyType()));

            poetWriter.increaseIndent();
            field.docs().ifPresent(docs -> {
                poetWriter.writeIndentedLine("\"\"\"");
                poetWriter.writeIndentedLine(docs.get().trim());
                poetWriter.writeIndentedLine("\"\"\"");
            });
            poetWriter.writeIndentedLine(String.format(
                    "return self._%s", PythonIdentifierSanitizer.sanitize(field.attributeName(), PROTECTED_FIELDS)));
            poetWriter.decreaseIndent();
        });

        // end of class def
        poetWriter.decreaseIndent();
        poetWriter.writeLine();
        poetWriter.writeLine();

        PythonClassRenamer.renameClass(poetWriter, className(), definitionPackage(), definitionName());
    }

    class Builder extends ImmutableBeanSnippet.Builder {}

    static Builder builder() {
        return new Builder();
    }
}
