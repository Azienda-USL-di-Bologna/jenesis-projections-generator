package it.bologna.ausl.jenesisprojections.generator;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.google.common.base.CaseFormat;
import it.bologna.ausl.jenesisprojections.generator.exceptions.FieldNotFoundException;
import it.nextsw.common.annotations.GenerateProjections;
import it.nextsw.common.utils.ForeignKey;
import net.sourceforge.jenesis4java.*;
import net.sourceforge.jenesis4java.jaloppy.JenesisJalopyEncoder;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.Formula;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.persistence.Version;

/**
 * @author gdm
 */
@SupportedAnnotationTypes({"javax.persistence.*"})
public class ProjectionProcessor extends AbstractProcessor {

    private static final List<String> TO_EXPAND = new ArrayList();
    private RoundEnvironment roundEnv;
    Set<? extends Element> entityElements;

    private final File outputJavaDirectory;
    private final String relativeTargetPackage;

    static {
        TO_EXPAND.add(javax.persistence.OneToMany.class.getName());
        TO_EXPAND.add(javax.persistence.ManyToOne.class.getName());
        TO_EXPAND.add(javax.persistence.OneToOne.class.getName());
        TO_EXPAND.add(javax.persistence.ManyToMany.class.getName());
    }

    public ProjectionProcessor(File outputJavaDirectory, String relativeTargetPackage) {
        this.outputJavaDirectory = outputJavaDirectory;
        this.relativeTargetPackage = relativeTargetPackage;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        this.roundEnv = roundEnv;
        if (!this.outputJavaDirectory.exists()) {
            if (!this.outputJavaDirectory.mkdirs()) {
                System.err.println("Could not create source directory!");
                return false;
            }
        }
        try {
            this.entityElements = roundEnv.getElementsAnnotatedWith(Entity.class);
            generateJavaCode();
            return true;
        } catch (Exception ex) {
            System.err.println("errore nella generazione delle projections");
            ex.printStackTrace(System.err);
            return false;
        }
    }

    private void generateJavaCode() throws IOException, ClassNotFoundException, FieldNotFoundException {
        System.setProperty("jenesis.encoder", JenesisJalopyEncoder.class.getName());

        System.out.println("generazione projections...");

        // Get the VirtualMachine implementation.
        VirtualMachine vm = VirtualMachine.getVirtualMachine();

        discoverEntitiesAndGenerate(vm);
    }

    private void discoverEntitiesAndGenerate(VirtualMachine vm) throws ClassNotFoundException, IOException, FieldNotFoundException {
        for (Element entity : this.entityElements) {
            System.out.println("processing: " + entity);
            readEntityAndCreateProjections(entity, vm);
        }
    }
    
    private void readEntityAndCreateProjections(Element entity, VirtualMachine vm) throws IOException, ClassNotFoundException, FieldNotFoundException {
        List<Element> plainFields = new ArrayList<>();
        List<Element> fkField = new ArrayList<>();
        List<List<Element>> fieldsToProjectionize = new ArrayList<>();
        
        List<? extends Element> enclosedElements = getElementsOfHierarchy(entity);
        for (Element enclosedElement : enclosedElements) {
            if (enclosedElement.getKind() == ElementKind.FIELD) {
                if ((isColumnField(enclosedElement) || isEmbeddedField(enclosedElement) ||
                        isTransientField(enclosedElement) || isFormulaField(enclosedElement) || isVersionField(enclosedElement))
                        && !isJsonIgnoreField(enclosedElement)) {
//                        System.out.println("found column field: " + enclosedElement);
                    plainFields.add(enclosedElement);
                } else if (isForeignKeysField(enclosedElement)) {
//                        System.out.println("found foreign key field: " + enclosedElement);
                    fkField.add(enclosedElement);
//                        System.out.println("-------------");
                }
            }
        }
        
        plainFields.sort((Element f1, Element f2) -> {
            PackageElement f1Package = processingEnv.getElementUtils().getPackageOf(f1);
            PackageElement f2Package = processingEnv.getElementUtils().getPackageOf(f2);
            String f1Name = f1Package.getQualifiedName().toString() + "." + f1.getSimpleName();
            String f2Name = f2Package.getQualifiedName().toString() + "." + f2.getSimpleName();
            return f1Name.compareToIgnoreCase(f2Name);
        });
        fkField.sort((Element f1, Element f2) -> {
            PackageElement f1Package = processingEnv.getElementUtils().getPackageOf(f1);
            PackageElement f2Package = processingEnv.getElementUtils().getPackageOf(f2);
            String f1Name = f1Package.getQualifiedName().toString() + "." + f1.getSimpleName();
            String f2Name = f2Package.getQualifiedName().toString() + "." + f2.getSimpleName();
            return f1Name.compareToIgnoreCase(f2Name);
        });
        
        String projectionClassName = generateProjectionClassWithPlainFields(vm, entity, plainFields, fkField);

        // se trovo l'annotazione GenerateProjections, genero solo le projection indicate nell'annotazione, altrimenti le genero tutte
        GenerateProjections generateProjectionsAnnotarion = entity.getAnnotation(GenerateProjections.class);
        if (generateProjectionsAnnotarion != null) {
            String[] projectionsToGenerate = generateProjectionsAnnotarion.value();
            for (String projectionToGenerate: projectionsToGenerate) {
                System.out.println("projectionToGenerate -> " + projectionToGenerate);
                String[] projectionFields = projectionToGenerate.split(",");
                List<Element> projectionFieldsList = new ArrayList<>();
                for (String projectionField : projectionFields) {
                    Optional<? extends Element> elementOp = enclosedElements.stream().filter(element -> element.getKind() == ElementKind.FIELD && element.getSimpleName().toString().equals(StringUtils.trim(projectionField))).findFirst();
                    if (elementOp.isPresent()) {
                        projectionFieldsList.add(elementOp.get());
                    } else {
                        throw new FieldNotFoundException("campo " + projectionField + " non trovato");
                    }
                }
                fieldsToProjectionize.add(projectionFieldsList);
            }
            createProjections(vm, entity, projectionClassName, fieldsToProjectionize);
        } else {
            discoverAndCreateProjections(vm, entity, projectionClassName, null, fkField);
        }
    }
    
    private void createProjections(VirtualMachine vm, Element entity, String projectionPlainFieldsClassName, List<List<Element>> fieldsToprojectionizeList) throws IOException, ClassNotFoundException {
        for (List<Element> projectionFields : fieldsToprojectionizeList) {
            projectionFields.sort((Element f1, Element f2) -> {
                PackageElement f1Package = processingEnv.getElementUtils().getPackageOf(f1);
                PackageElement f2Package = processingEnv.getElementUtils().getPackageOf(f2);
                String f1Name = f1Package.getQualifiedName().toString() + "." + f1.getSimpleName();
                String f2Name = f2Package.getQualifiedName().toString() + "." + f2.getSimpleName();
                return f1Name.compareToIgnoreCase(f2Name);
            });
            generateProjectionClassWithForeignKeysMethods(vm, entity, projectionPlainFieldsClassName, projectionFields);
        }
    }

    /**
     * Il metodo si occupa di scorrere tutta la gerarchia della classe e chiama il metodo
     * {@link TypeElement#getEnclosedElements()} su tutte le classi della gerarchia che sono annotate
     * con {@link Entity} o con {@link MappedSuperclass}, mettendo i valori ottenuti dentro una lista
     * che viene restitutita come risulatato
     *
     * @param element l'elemento su cui deve essere chiamata la funzione
     * @return
     */
    protected List<? extends Element> getElementsOfHierarchy(Element element) {
        Element el = element;
        final List<Element> result = new ArrayList<>();
        // metodo di uscita del while calcolato empiricamente, se ci sono problemi potrebbero essere qui
        while (!el.toString().equals("java.lang.Object")) {
            new SimpleElementVisitor8<Void, Void>() {
                @Override
                public Void visitType(TypeElement e, Void p) {
                    if (e.getAnnotation(Entity.class) != null || e.getAnnotation(MappedSuperclass.class) != null)
                        result.addAll(e.getEnclosedElements());
                    return null;
                }
            }.visit(el);
            el = ((DeclaredType) ((TypeElement) el).getSuperclass()).asElement();
        }
        return result;
    }

    private boolean isColumnField(Element field) {
        return field.getAnnotation(javax.persistence.Column.class) != null;
    }

    private boolean isEmbeddedField(Element field) {
        return field.getAnnotation(javax.persistence.Embedded.class) != null;
    }
    
    private boolean isTransientField(Element field) {
        return field.getAnnotation(javax.persistence.Transient.class) != null;
    }

    private boolean isFormulaField(Element field) {
        return field.getAnnotation(Formula.class) != null;
    }

    private boolean isVersionField(Element field) {
        return field.getAnnotation(Version.class) != null;
    }
    
    private boolean isJsonIgnoreField(Element field) {
        return field.getAnnotation(com.fasterxml.jackson.annotation.JsonIgnore.class) != null;
    }

    private boolean isForeignKeysField(Element field) {
        List<? extends AnnotationMirror> annotations = processingEnv.getElementUtils().getAllAnnotationMirrors(field);

        for (AnnotationMirror annotation : annotations) {
            String annotationName = ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
            //System.out.println("annotation: " + annotation + " - type: " + (annotation.annotationType() == javax.persistence.OneToMany.class));
            if (TO_EXPAND.contains(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private String getType(Element fkField) throws ClassNotFoundException, IOException {
        String type;
        if (null == fkField.asType().getKind()) {
            type = fkField.asType().toString();
        } else {
            switch (fkField.asType().getKind()) {
                case DECLARED:
                    TypeElement name = (TypeElement) processingEnv.getTypeUtils().asElement(fkField.asType());
                    Name qualifiedName = name.getQualifiedName();
                    if (entityElements.stream().anyMatch(element -> {
                        String entityQualifiedName = processingEnv.getElementUtils().getPackageOf(element) + "." + element.getSimpleName().toString();
                        return entityQualifiedName.equals(qualifiedName.toString());
                    })) {
                        // ï¿½ un entitï¿½ 
                        type = qualifiedName.toString();
                    } else
                        type = qualifiedName.toString();
                    break;
                case ARRAY:
                    // se ï¿½ un array con annotazione, il stringa rappresentante il tipo conterrï¿½ anche l'annotazione, quindi estraggo il tipo con un'espressione regolare
                    type = getArrayType(fkField);
                    break;
                default:
                    //            System.out.println("ï¿½ ancora altro; " + fkField.asType());
                    type = fkField.asType().toString();
                    break;
            }
        }
        return type;
    }

    /**
     * estrare il tipo dell'array con espressione regolare
     *
     * @param fkField
     * @return
     * @throws IOException
     */
    private String getArrayType(Element fkField) throws IOException {
        String regex = "(?>\\(?@[\\w\\.]+\\s*:*\\s+)?([\\w\\.]+)\\)?(\\[\\])?";
        Pattern r = Pattern.compile(regex);
        Matcher m = r.matcher(fkField.asType().toString());
        String type;
        if (m.find()) {
            type = m.group(1);
            if (StringUtils.isNotEmpty(m.group(2)))
                type += m.group(2);
        } else {
            throw new IOException(String.format("errore nel reperimento del tipo dell'array %s", fkField.asType().toString()));
        }
        return type;
    }

    private boolean isEntityFieldType(String type) {
        return (entityElements.stream().anyMatch(element -> {
            String entityQualifiedName = processingEnv.getElementUtils().getPackageOf(element) + "." + element.getSimpleName().toString();
            return entityQualifiedName.equals(type);
        }));
    }

    private boolean isArrayFieldType(String type) {
        return type.endsWith("[]");
    }

    private boolean isCollectionFieldType(String type) {
        try {
            return Collection.class.isAssignableFrom(Class.forName(type));
        } catch (Exception ex) {
            return false;
        }
    }

    private String generateProjectionClassWithPlainFields(VirtualMachine vm, Element sourceEntity, List<Element> plainFields, List<Element> additionalFields) throws IOException, ClassNotFoundException {

        String targetClassName = sourceEntity.getSimpleName() + "WithPlainFields";
        System.out.println("targetClassName: " + targetClassName);

        String generateProjectionClassName = generateProjectionClass(vm, sourceEntity, targetClassName, null, plainFields, additionalFields);
        return generateProjectionClassName;
    }

    private String generateProjectionClassWithForeignKeysMethods(VirtualMachine vm, Element sourceEntityClass, String projectionPlainFieldsClassName, List<Element> fieldsToExpand) throws IOException, ClassNotFoundException {

        String targetClassName = sourceEntityClass.getSimpleName() + "With";
        for (Element field : fieldsToExpand) {
            String fieldIdentifier = field.getSimpleName().toString();
            targetClassName += CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, fieldIdentifier) + "And";
        }
        targetClassName = targetClassName.substring(0, targetClassName.lastIndexOf("And"));
        
        System.out.println("targetClassName: " + targetClassName);
        if (targetClassName.length() > 200) {
            System.out.println("Skip della creazione projection per nome troppo lungo");
            return null;
        }
        
        String generateProjectionClassName = generateProjectionClass(vm, sourceEntityClass, targetClassName, projectionPlainFieldsClassName, fieldsToExpand, null);
        return generateProjectionClassName;
    }

    private String generateProjectionClass(VirtualMachine vm, Element sourceEntity, String targetClassName, String projectionPlainFieldsClassName, List<Element> fields, List<Element> additionalFields) throws IOException, ClassNotFoundException {

        String packageName = processingEnv.getElementUtils().getPackageOf(sourceEntity).getQualifiedName().toString();
        String entityClassQualifiedName = packageName + "." + sourceEntity.getSimpleName();
        String targetPackage = packageName + "." + relativeTargetPackage;
        String targetCanonicalClassName = targetPackage + "." + targetClassName;
        String targetProjectionClassFilePath = targetCanonicalClassName.replace(".", "/") + ".java";
        System.out.println("targetProjectionClassFilePath: " + targetProjectionClassFilePath);

        File generatedFile = new File(outputJavaDirectory, targetProjectionClassFilePath);

        // delete the file if it already exists from a previous run
        if (generatedFile.exists()) {
            generatedFile.delete();
        }

        // Instantiate a new CompilationUnit. The argument to the
        // compilation unit is the "codebase" or directory where the
        // compilation unit should be written.
        //
        // Make a new compilation unit rooted to the given sourcepath.
        CompilationUnit unit = vm.newCompilationUnit(outputJavaDirectory.getCanonicalPath());

        // Set the package namespace.
        unit.setNamespace(targetPackage);

        unit.addImport(entityClassQualifiedName);
        unit.addImport("org.springframework.data.rest.core.config.Projection");
        unit.addImport("org.springframework.beans.factory.annotation.Value");
        unit.addImport("com.fasterxml.jackson.annotation.JsonFormat");

        // Comment the package with a javadoc (DocumentationComment).
        unit.setComment(Comment.DOCUMENTATION, "Auto-Generated using the Jenesis Syntax API");

        // Make a new class.
//        PackageClass cls = unit.newClass("HelloWorld");
        Interface cls = unit.newInterface(targetClassName);
        // Make it a public class.
        cls.setAccess(Access.PUBLIC);

        if (projectionPlainFieldsClassName != null) {
            cls.addExtends(projectionPlainFieldsClassName);
        }

        //@Projection(name = "strutturaConUtenti", types = Struttura.class)
        //Annotation
        cls.addAnnotation(String.format("Projection(name = \"%s\", types = %s.class)", targetClassName, entityClassQualifiedName));

        for (Element field : fields) {

            JsonFormat.Shape shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING;
            String dateTimePattern = "yyyy-MM-dd'T'HH:mm:ss";
            String datePattern = "yyyy-MM-dd";
            String jsonFormatTemplate = "JsonFormat(shape = %s, pattern = \"%s\")";
            String methodName = "get" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, field.getSimpleName().toString());
            String fieldTypeString = getType(field);
            boolean isEntityFieldType = isEntityFieldType(fieldTypeString);
            boolean isArrayFieldType = !isEntityFieldType && isArrayFieldType(fieldTypeString);
            boolean isCollectionFieldType = !isEntityFieldType && !isArrayFieldType && isCollectionFieldType(fieldTypeString);
            String returnType;
            String interceptorAnnotation = null;

            // se additionalField è diverso da null vuol dire che sto generando la projection base (WithPlainFields)
            if (additionalFields != null) {
                // quindi controllo se il campo che metterò è Transient, se loè metto come tipo di ritono Object
                if (isTransientField(field))
                    returnType = Object.class.getCanonicalName();
                else // altrimenti metto il tipo vero
                    returnType = fieldTypeString;
            } else { // nei campi espansione metto Object
                returnType = Object.class.getCanonicalName();
                if (isCollectionFieldType) {
                    interceptorAnnotation = String.format("Value(\"#{@projectionsInterceptorLauncher.lanciaInterceptorCollection(target, '%s')}\")", methodName);
                } else {
                    interceptorAnnotation = String.format("Value(\"#{@projectionsInterceptorLauncher.lanciaInterceptor(target, '%s')}\")", methodName);
                }
            }
            //vm.newType(Type.VOID);
            AbstractMethod method = cls.newMethod(vm.newType(returnType), methodName);
            if (interceptorAnnotation != null) {
                method.addAnnotation(interceptorAnnotation);
            }

            // se non si riesce a istanziare la classe allora vuol dire che il tipo ï¿½ un tipo custom (cioï¿½ un tipo non base di java, ma non un entitï¿½, es. un enum in una classe)
            boolean customType = false;
            try {
                Class.forName(fieldTypeString);
            } catch (ClassNotFoundException classNotFoundException) {
                customType = true;
            }

//            AbstractMethod method = cls.newMethod(vm.newType(Object.class), methodName);
            if (!isEntityFieldType && !isArrayFieldType && !customType) {
                Class fieldType = Class.forName(fieldTypeString);
                if (LocalDate.class.isAssignableFrom(fieldType) || LocalDateTime.class.isAssignableFrom(fieldType) || ZonedDateTime.class.isAssignableFrom(fieldType)) {
                    JsonFormat jsonFormatAnnotation = field.getAnnotation(JsonFormat.class);

                    if (jsonFormatAnnotation != null) {
                        shape = jsonFormatAnnotation.shape();
                        if (LocalDate.class.isAssignableFrom(fieldType)) {
                            datePattern = jsonFormatAnnotation.pattern();
                        } else {
                            dateTimePattern = jsonFormatAnnotation.pattern();
                        }
                    }

                    String annotationString = String.format(jsonFormatTemplate, shape.getClass().getCanonicalName() + "." + shape, LocalDate.class.isAssignableFrom(fieldType) ? datePattern : dateTimePattern);

                    method.addAnnotation(annotationString);
                }
            }
            // Make it a public method.
            method.setAccess(Access.PUBLIC);
        }

        if (additionalFields != null) {
            for (Element additionalField : additionalFields) {
                String methodName = "getFK_" + additionalField.getSimpleName().toString();
                String returnType = ForeignKey.class.getCanonicalName();
                //vm.newType(Type.VOID);
//                if (targetClassName.equalsIgnoreCase("PersonaWithPlainFields")) {
//                    System.out.println("fieldTypeString: " + returnType);
//                }
                AbstractMethod method = cls.newMethod(vm.newType(returnType), methodName);
                method.addAnnotation(String.format("Value(\"#{@foreignKeyExporter.toForeignKey('%s', target)}\")", additionalField.getSimpleName().toString()));

                // Make it a public method.
                method.setAccess(Access.PUBLIC);
            }
        }
        
        unit.encode();
        return targetCanonicalClassName;
    }

    private void discoverAndCreateProjections(VirtualMachine vm, Element entity, String projectionPlainFieldsClassName, List<Element> p1, List<Element> p2) throws IOException, ClassNotFoundException {
        if (p1 != null && !p1.isEmpty()) {
            generateProjectionClassWithForeignKeysMethods(vm, entity, projectionPlainFieldsClassName, p1);
        }
        if (p2 != null && !p2.isEmpty()) {
            for (int i = 0; i < p2.size(); i++) {
                List<Element> pp1;
                if (p1 != null) {
                    pp1 = new ArrayList<>(p1);
                } else {
                    pp1 = new ArrayList<>();
                }
                pp1.add(p2.get(i));

                List<Element> pp2 = new ArrayList<>();
                for (int j = i + 1; j < p2.size(); j++) {
                    pp2.add(p2.get(j));
                }
                discoverAndCreateProjections(vm, entity, projectionPlainFieldsClassName, pp1, pp2);
            }
        }
    }

}
