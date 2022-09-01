/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package it.bologna.ausl.jenesisprojections.generator;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.persistence.Entity;

/**
 *
 * @author gdm
 */
@SupportedAnnotationTypes({"javax.persistence.*"})
public class TestProcessor extends AbstractProcessor{
private static final List<String> TO_EXPAND = new ArrayList();
    private File outputJavaDirectory;
    
    static {
        TO_EXPAND.add(javax.persistence.OneToMany.class.getName());
        TO_EXPAND.add(javax.persistence.ManyToOne.class.getName());
        TO_EXPAND.add(javax.persistence.OneToOne.class.getName());
        TO_EXPAND.add(javax.persistence.ManyToMany.class.getName());
    }
    private String parametro;
    
    public TestProcessor(String parametro) {
        this.parametro = parametro;
    }
    
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            Class<?> forName = Class.forName("it.bologna.ausl.model.entities.scrivania.Attivita");
            System.out.println("forName: " + forName.getCanonicalName());
        } catch (ClassNotFoundException ex) {
        }
        System.out.println("a " + processingEnv.getElementUtils().getTypeElement("java.util.List").asType());
        System.out.println("b " + processingEnv.getElementUtils().getTypeElement("java.util.Collection").asType());
        System.out.println("c " + processingEnv.getTypeUtils().isAssignable(processingEnv.getElementUtils().getTypeElement("java.util.List").asType(), processingEnv.getElementUtils().getTypeElement("java.util.Collection").asType()));
        System.out.println("d " + processingEnv.getTypeUtils().isAssignable(processingEnv.getElementUtils().getTypeElement("java.util.Collection").asType(), processingEnv.getElementUtils().getTypeElement("java.util.List").asType()));
    try {
        System.out.println("e " + Class.forName("java.util.List").isAssignableFrom(Class.forName("java.util.Collection")));
        System.out.println("f " + Class.forName("java.util.Collection").isAssignableFrom(Class.forName("java.util.List")));
    } catch (ClassNotFoundException ex) {
        Logger.getLogger(TestProcessor.class.getName()).log(Level.SEVERE, null, ex);
    }
        System.out.println("getTypeUtils");
        processingEnv.getTypeUtils();
        System.out.println("getElementUtils");
        processingEnv.getElementUtils();
        System.out.println("TestProcessor: " + roundEnv.getRootElements());
        Set<? extends Element> entityElements = roundEnv.getElementsAnnotatedWith(Entity.class);
        System.out.println("TestProcessor: " + entityElements);
        for (Element e : entityElements) {
            System.out.println("entità: " + processingEnv.getElementUtils().getPackageOf(e) + "." + e.getSimpleName());
            List<Element> fkFields = new ArrayList<>();
            List<Element> plainFields = new ArrayList<>();
            for (Element enclosedElement : e.getEnclosedElements()) {
                if (enclosedElement.getKind() == ElementKind.FIELD) {
                    try {
                        fkFields.add(enclosedElement);
//                            else
//                                plainFields.add(enclosedElement);
                    } catch (Exception e3) {
                    }
                }
            }
            System.out.println("befiore: ");
            System.out.println(fkFields.toString());
            fkFields.sort((Element f1, Element f2) -> {
                PackageElement f1Package = processingEnv.getElementUtils().getPackageOf(f1);
                PackageElement f2Package = processingEnv.getElementUtils().getPackageOf(f2);
                String f1Name = f1Package.getQualifiedName().toString() + "." + f1.getSimpleName();
                String f2Name = f2Package.getQualifiedName().toString() + "." + f2.getSimpleName();
                return f1Name.compareToIgnoreCase(f2Name);
            });
            System.out.println("after: ");
            System.out.println(fkFields.toString());
            for (Element fkField : fkFields) {
                try {
                System.out.println("fkField.getSimpleName(): " + fkField.getSimpleName());
                    System.out.println("type: " + getType(fkField, entityElements));
                } catch (Exception ex) {
                    Logger.getLogger(TestProcessor.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
//        roundEnv.getElementsAnnotatedWith(Entity.class).stream().forEach(e -> {e.getKind()});
        System.out.println("TestProcessor: " + roundEnv.processingOver());
        System.out.println("TestProcessor: " + annotations);
        System.out.println("TestProcessor paramtro: " + parametro);
//        System.out.println("TestProcessor: " + roundEnv.getRootElements());
        return true;
    }
    
    private String getType(Element fkField, Set<? extends Element> entityElements) throws ClassNotFoundException {
        String type;
        if (fkField.asType().getKind() == TypeKind.DECLARED) {
            TypeElement name  = (TypeElement) processingEnv.getTypeUtils().asElement(fkField.asType());
            Name qualifiedName = name.getQualifiedName();
//            System.out.println("getQualifiedName: " + qualifiedName);
            if (entityElements.stream().anyMatch(element -> {
                String entityQualifiedName = processingEnv.getElementUtils().getPackageOf(element) + "." + element.getSimpleName().toString();
                return entityQualifiedName.equals(qualifiedName.toString());
            })
            ) {
//                System.out.println("è un entità: " + qualifiedName);
                type = qualifiedName.toString();
            }
//            else if (processingEnv.getTypeUtils().isAssignable(name.asType(), processingEnv.getElementUtils().getTypeElement("java.util.Collection").asType())) {
            else if (Collection.class.isAssignableFrom(Class.forName(qualifiedName.toString()))) {
//                System.out.println("è altro; " + qualifiedName);
                type = qualifiedName.toString();
            }
            else {
                // è Integer, String o altro
                type = qualifiedName.toString();
            }
        } else if (fkField.asType().getKind() == TypeKind.ARRAY) {
//            if (fkField.asType().toString().contains("@")) {
                type = fkField.asType().toString().replaceAll("@[\\w\\.]+", "");
//                System.out.println("è array: " + type);
//            }
        } else {
//            System.out.println("è ancora altro; " + fkField.asType());
            type = fkField.asType().toString();
        }
        return type;
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
        
    private boolean isColumnField(Element field) {
        java.lang.annotation.Annotation annotation = field.getAnnotation(javax.persistence.Column.class);
        return annotation != null;
    }
    
}
