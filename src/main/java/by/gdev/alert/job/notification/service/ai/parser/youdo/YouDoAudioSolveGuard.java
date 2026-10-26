package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.common.service.playwright.captcha.SmartCaptchaAudioSolveGuard;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class YouDoAudioSolveGuard implements SmartCaptchaAudioSolveGuard {

    private final YouDoLoginApiListener loginApiListener;

    @Override
    public void beforeAudioSolve(Page page) {
        loginApiListener.end(page);
    }

    @Override
    public void afterAudioSolve(Page page) {
        loginApiListener.begin(page);
    }
}
