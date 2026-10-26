package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.common.service.playwright.captcha.CaptchaService;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Перехват капчи на время логина YouDo.
 * {@link CaptchaService#beginKaleidoscopeNetworkCapture} должен жить с первого шага до выхода из логина,
 * включая ранние {@code return}: task и картинка калейдоскопа приходят в {@code /check} до solve.
 * Рядом слушается XHR YouDo ({@link YouDoLoginApiListener}), тела ответов капчи он не читает.
 */
@Component
@RequiredArgsConstructor
public class YouDoLoginCaptcha {

    private final CaptchaService captchaService;

    private final YouDoLoginApiListener loginApiListener;

    public void beginNetworkCapture(Page page) {
        captchaService.beginKaleidoscopeNetworkCapture(page);
        loginApiListener.begin(page);
    }

    public void endNetworkCapture(Page page) {
        captchaService.endKaleidoscopeNetworkCapture(page);
        loginApiListener.end(page);
    }
}
