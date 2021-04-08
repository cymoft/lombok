package lombok.extern.validation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.validation.Payload;


@Retention(SOURCE) @Target(METHOD) public @interface ValidateMethod {
    String message() default "";

    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};

}
