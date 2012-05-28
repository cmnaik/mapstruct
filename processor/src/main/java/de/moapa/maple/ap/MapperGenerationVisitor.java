/**
 *  Copyright 2012 Gunnar Morling (http://www.gunnarmorling.de/)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package de.moapa.maple.ap;

import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementKindVisitor6;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleAnnotationValueVisitor6;
import javax.lang.model.util.TypeKindVisitor6;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;

import de.moapa.maple.ap.model.Binding;
import de.moapa.maple.ap.model.Mapper;
import de.moapa.maple.ap.model.MapperMethod;
import de.moapa.maple.ap.model.Parameter;
import de.moapa.maple.ap.model.Type;
import freemarker.template.Configuration;
import freemarker.template.Template;

import static javax.lang.model.util.ElementFilter.methodsIn;

public class MapperGenerationVisitor extends ElementKindVisitor6<Void, Void> {

	private final static String IMPLEMENTATION_SUFFIX = "Impl";

	private final static String TEMPLATE_NAME = "dozer-mapper-implementation.ftl";

	private final static String MAPPING_ANNOTATION = "de.moapa.maple.Mapping";

	private final static String MAPPINGS_ANNOTATION = "de.moapa.maple.Mappings";

	private final static String CONVERTER_TYPE = "de.moapa.maple.converter.Converter";

	private final ProcessingEnvironment processingEnvironment;

	private final Configuration configuration;

	private final Types typeUtils;

	private final Elements elementUtils;

	public MapperGenerationVisitor(ProcessingEnvironment processingEnvironment, Configuration configuration) {

		this.processingEnvironment = processingEnvironment;
		this.typeUtils = processingEnvironment.getTypeUtils();
		this.elementUtils = processingEnvironment.getElementUtils();

		this.configuration = configuration;
	}

	@Override
	public Void visitTypeAsInterface(TypeElement element, Void p) {

		Mapper model = retrieveModel( element );
		String sourceFileName = element.getQualifiedName() + IMPLEMENTATION_SUFFIX;

		writeModelToSourceFile( sourceFileName, model );

		return null;
	}

	private void writeModelToSourceFile(String fileName, Mapper model) {
		try {
			JavaFileObject sourceFile = processingEnvironment.getFiler().createSourceFile( fileName );
			BufferedWriter writer = new BufferedWriter( sourceFile.openWriter() );

			Template template = configuration.getTemplate( TEMPLATE_NAME );
			template.process( model, writer );
			writer.flush();
			writer.close();
		}
		catch ( Exception e ) {
			throw new RuntimeException( e );
		}
	}

	private Mapper retrieveModel(TypeElement element) {

		return new Mapper(
				elementUtils.getPackageOf( element ).getQualifiedName().toString(),
				element.getSimpleName() + IMPLEMENTATION_SUFFIX,
				element.getSimpleName().toString(),
				retrieveMethods( element )
		);
	}

	private List<MapperMethod> retrieveMethods(TypeElement element) {

		List<MapperMethod> methods = new ArrayList<MapperMethod>();

		for ( ExecutableElement oneMethod : methodsIn( element.getEnclosedElements() ) ) {

			Type returnType = retrieveReturnType( oneMethod );
			Parameter parameter = retrieveParameter( oneMethod );
			List<Binding> bindings = retrieveBindings( oneMethod );
			methods.add(
					new MapperMethod(
							oneMethod.getSimpleName().toString(),
							returnType,
							parameter,
							bindings
					)
			);
		}

		return methods;
	}

	private List<Binding> retrieveBindings(ExecutableElement method) {

		List<Binding> bindings = new ArrayList<Binding>();

		for ( AnnotationMirror annotationMirror : method.getAnnotationMirrors() ) {

			String annotationName = annotationMirror.getAnnotationType()
					.asElement()
					.accept( new NameDeterminationVisitor(), null );

			if ( MAPPING_ANNOTATION.equals( annotationName ) ) {
				bindings.add( retrieveBinding( annotationMirror ) );
			}
			else if ( MAPPINGS_ANNOTATION.equals( annotationName ) ) {
				bindings.addAll( retrieveBindings( annotationMirror ) );
			}
		}

		return bindings;
	}

	private List<Binding> retrieveBindings(AnnotationMirror annotationMirror) {

		List<Binding> bindings = new ArrayList<Binding>();

		for ( Entry<? extends ExecutableElement, ? extends AnnotationValue> oneAttribute : annotationMirror.getElementValues()
				.entrySet() ) {

			if ( oneAttribute.getKey().getSimpleName().contentEquals( "value" ) ) {
				List<? extends AnnotationValue> values = oneAttribute.getValue()
						.accept( new AnnotationValueListRetrievingVisitor(), null );

				for ( AnnotationValue oneAnnotationValue : values ) {
					AnnotationMirror oneAnnotation = oneAnnotationValue.accept(
							new AnnotationRetrievingVisitor(),
							null
					);
					bindings.add( retrieveBinding( oneAnnotation ) );
				}
			}
		}

		return bindings;
	}

	private Binding retrieveBinding(AnnotationMirror annotationMirror) {

		String sourcePropertyName = null;
		String targetPropertyName = null;
		Type converterType = null;
		Type sourceType = null;
		Type targetType = null;

		for ( Entry<? extends ExecutableElement, ? extends AnnotationValue> oneAttribute : annotationMirror.getElementValues()
				.entrySet() ) {

			if ( oneAttribute.getKey().getSimpleName().contentEquals( "source" ) ) {
				sourcePropertyName = oneAttribute.getValue().accept( new StringValueRetrievingVisitor(), null );
			}
			else if ( oneAttribute.getKey().getSimpleName().contentEquals( "target" ) ) {
				targetPropertyName = oneAttribute.getValue().accept( new StringValueRetrievingVisitor(), null );
			}
			else if ( oneAttribute.getKey().getSimpleName().contentEquals( "converter" ) ) {
				TypeMirror converterTypeMirror = oneAttribute.getValue()
						.accept(
								new TypeRetrievingVisitor(), null
						);

				converterType = getType( typeUtils.asElement( converterTypeMirror ) );

				List<? extends TypeMirror> converterTypeParameters = getTypeParameters(
						converterTypeMirror,
						CONVERTER_TYPE
				);

				sourceType = getType( typeUtils.asElement( converterTypeParameters.get( 0 ) ) );
				targetType = getType( typeUtils.asElement( converterTypeParameters.get( 1 ) ) );
			}
		}

		return new Binding( sourceType, sourcePropertyName, targetType, targetPropertyName, converterType );
	}

	private Type getType(Element sourceTypeElement) {
		return new Type(
				elementUtils.getPackageOf( sourceTypeElement ).toString(),
				sourceTypeElement.getSimpleName().toString()
		);
	}

	//TODO: consider complete type hierarchy
	private List<? extends TypeMirror> getTypeParameters(TypeMirror type, String superTypeName) {

		for ( TypeMirror oneSuperType : typeUtils.directSupertypes( type ) ) {
			String oneSuperTypeName = typeUtils.asElement( oneSuperType )
					.accept( new NameDeterminationVisitor(), null );

			if ( oneSuperTypeName.equals( superTypeName ) ) {
				return oneSuperType.accept( new TypeParameterDeterminationVisitor(), null );
			}
		}

		return Collections.emptyList();
	}

	private Parameter retrieveParameter(ExecutableElement method) {

		List<? extends VariableElement> parameters = method.getParameters();

		if ( parameters.size() != 1 ) {
			//TODO: Log error
			return null;
		}

		VariableElement parameter = parameters.get( 0 );

		return new Parameter(
				parameter.getSimpleName().toString(),
				new Type(
						elementUtils.getPackageOf( parameter ).getQualifiedName().toString(),
						typeUtils.asElement( parameter.asType() ).getSimpleName().toString()
				)
		);
	}

	private Type retrieveReturnType(ExecutableElement method) {

		Element returnTypeElement = typeUtils.asElement( method.getReturnType() );

		return new Type(
				elementUtils.getPackageOf( returnTypeElement ).getQualifiedName().toString(),
				returnTypeElement.getSimpleName().toString()
		);
	}

	private static class TypeParameterDeterminationVisitor extends TypeKindVisitor6<List<? extends TypeMirror>, Void> {

		@Override
		public List<? extends TypeMirror> visitDeclared(DeclaredType type, Void p) {
			return type.getTypeArguments();
		}
	}

	private static class NameDeterminationVisitor extends ElementKindVisitor6<String, Void> {

		@Override
		public String visitType(TypeElement element, Void p) {
			return element.getQualifiedName().toString();
		}
	}

	private static class StringValueRetrievingVisitor extends SimpleAnnotationValueVisitor6<String, Void> {

		@Override
		public String visitString(String value, Void p) {
			return value;
		}
	}

	private static class TypeRetrievingVisitor
			extends SimpleAnnotationValueVisitor6<TypeMirror, Void> {

		@Override
		public TypeMirror visitType(TypeMirror value, Void p) {
			return value;
		}
	}

	private static class AnnotationValueListRetrievingVisitor
			extends SimpleAnnotationValueVisitor6<List<? extends AnnotationValue>, Void> {

		@Override
		public List<? extends AnnotationValue> visitArray(List<? extends AnnotationValue> value, Void p) {
			return value;
		}
	}

	private static class AnnotationRetrievingVisitor extends SimpleAnnotationValueVisitor6<AnnotationMirror, Void> {

		@Override
		public AnnotationMirror visitAnnotation(AnnotationMirror value, Void p) {
			return value;
		}
	}
}