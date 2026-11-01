package demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 关卡里让用户改的就是这类文件：一个路由 + 一段业务。 */
@RestController
public class HelloController {

    private final Greeter greeter = new Greeter();

    @GetMapping("/hello/{name}")
    public String hello(@PathVariable String name) {
        return greeter.greet(name);
    }
}
