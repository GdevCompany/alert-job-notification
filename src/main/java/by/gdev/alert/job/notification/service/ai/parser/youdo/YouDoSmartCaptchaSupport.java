package by.gdev.alert.job.notification.service.ai.parser.youdo;

import by.gdev.common.service.playwright.captcha.CaptchaService;
import by.gdev.common.service.playwright.captcha.failure.CaptchaFailureCode;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SmartCaptcha в модалке логина YouDo: чекбокс «Я не робот» и калейдоскоп.
 * <p>
 * Капча не привязана к одному шагу FSM. Она может встать на главной, на куках, в модалке,
 * после email, вместо поля кода и пока ждём письмо. Видимый пазл, advanced-iframe, оверлей
 * или чекбокс ({@link #hasCaptchaOnScreen}) блокируют шаг даже если в DOM уже есть длинный
 * smart-token: дополнительная проверка приходит новым кадром после верного первого пазла.
 * Скрытый {@code div} виджета после принятого токена шаг не держит.
 * <p>
 * Калейдоскоп решается в браузере сессии ({@code solveYouDoBrowserSessionKaleidoscope}).
 * Чекбокс — локальный клик, затем при необходимости внешний {@code YandexSmartCaptchaTask}.
 * Поле OTP на экране не отменяет решение, если рядом всё ещё видны пазл, advanced или чекбокс.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouDoSmartCaptchaSupport {

    public enum CaptchaKind {
        NONE,
        CHECKBOX,
        KALEIDOSCOPE,
        HIDDEN_SHELL
    }

    private static final String[] SMART_CAPTCHA_MARKERS = {
            "iframe[data-testid='checkbox-iframe']",
            "iframe[src*='smartcaptcha.cloud.yandex.ru/backend']",
            "iframe[data-testid='advanced-iframe']",
            "[data-testid='smartCaptcha-container']",
            ".smart-captcha[data-sitekey]"
    };

    private final CaptchaService captchaService;

    private final YouDoModalLayoutFix modalLayoutFix;

    private final YouDoPostCaptchaSubmit postCaptchaSubmit;

    @Value("${captcha.yandex.two-captcha-fallback:true}")
    private boolean yandexTwoCaptchaFallback;

    /** YouDo: сначала 2Captcha для checkbox и advanced; локальный клик — только fallback. */
    @Value("${captcha.youdo.two-captcha-first:false}")
    private boolean youdoTwoCaptchaFirst;

    /**
     * Капча на любом шаге логина YouDo: видимая, скрытая (грузится), pending.
     * В т.ч. {@code data-sentry-component="SmartCaptcha"} до появления iframe.
     */
    public boolean isLoginCaptchaGate(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)) {
            return true;
        }
        if (isSmartCaptchaStage(page)) {
            return true;
        }
        if (captchaService.smartCaptchaTokenLength(page) > 20) {
            // После kaleidoscope-токена часто остаётся чекбокс «Я не робот» — не считаем капчу снятой.
            return captchaService.isCheckboxIframeChallenge(page)
                    || captchaService.isYandexCheckboxVisible(page);
        }
        return isSmartCaptchaShellPresent(page);
    }

    /** SmartCaptcha в DOM (в т.ч. скрытый div до iframe) на форме логина. */
    public boolean isSmartCaptchaShellPresent(Page page) {
        return isSmartCaptchaShellInLoginModal(page) || isSmartCaptchaShellAnywhere(page);
    }

    /**
     * Нужно ли считать экран «стадией капчи» для FSM логина.
     * Скрытые iframe после успешного smart-token не блокируют переход к email/OTP.
     */
    public boolean blocksYouDoLoginStage(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (isSmartCaptchaStage(page)) {
            return true;
        }
        if (captchaService.smartCaptchaTokenLength(page) > 20) {
            return false;
        }
        return isLoginCaptchaGate(page) || isSmartCaptchaShellPresent(page);
    }

    /**
     * Что сейчас на экране логина.
     * {@link CaptchaKind#KALEIDOSCOPE} — пазл, advanced UI или уже пойманный task из {@code /check}.
     * {@link CaptchaKind#CHECKBOX} — «Я не робот» без пазла.
     * {@link CaptchaKind#HIDDEN_SHELL} — оболочка виджета или pending без видимого задания.
     */
    public CaptchaKind detectCaptchaKind(Page page) {
        if (page == null || page.isClosed()) {
            return CaptchaKind.NONE;
        }
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.hasCapturedPuzzleTask(page)) {
            return CaptchaKind.KALEIDOSCOPE;
        }
        if (captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page)) {
            return CaptchaKind.CHECKBOX;
        }
        if (isSmartCaptchaShellPresent(page) || captchaService.isSmartCaptchaPending(page)) {
            return CaptchaKind.HIDDEN_SHELL;
        }
        if (isLoginCaptchaGate(page)) {
            return CaptchaKind.HIDDEN_SHELL;
        }
        return CaptchaKind.NONE;
    }

    /**
     * Видимый challenge текущего шага: пазл, advanced, оверлей, чекбокс.
     * Сюда же входит pending, пока {@code getResponse()} короче 20 символов.
     * Скрытая оболочка после уже принятого токена не считается: иначе логин зациклится
     * на пустом виджете между email и кодом из письма.
     * Видимый калейдоскоп возвращает {@code true} даже при длинном токене — это следующий кадр,
     * а не остаток закрытой проверки.
     */
    public boolean hasCaptchaOnScreen(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            if (captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.isYandexAdvancedUiVisible(page)
                    || captchaService.isSmartCaptchaOverlayVisible(page)) {
                return true;
            }
            if (captchaService.isCheckboxIframeChallenge(page)
                    || captchaService.isYandexCheckboxVisible(page)) {
                return true;
            }
            return captchaService.isSmartCaptchaPending(page)
                    && captchaService.smartCaptchaApiTokenLength(page) < 20;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Капча блокирует вход (модалка, iframe, калейдоскоп, task из /check). */
    public boolean isSmartCaptchaStage(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)) {
            return true;
        }
        int tokenLen = captchaService.smartCaptchaTokenLength(page);
        if (tokenLen > 20) {
            int apiLen = captchaService.smartCaptchaApiTokenLength(page);
            if (apiLen >= 20) {
                return captchaService.isCheckboxIframeChallenge(page)
                        || captchaService.isYandexCheckboxVisible(page);
            }
            return captchaService.isSmartCaptchaPending(page)
                    || captchaService.hasCapturedPuzzleTask(page)
                    || captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.isYandexAdvancedUiVisible(page)
                    || captchaService.isSmartCaptchaOverlayVisible(page)
                    || captchaService.isCheckboxIframeChallenge(page)
                    || captchaService.isYandexCheckboxVisible(page);
        }
        if (hasVisibleSmartCaptchaUi(page)) {
            return true;
        }
        if (captchaService.isSmartCaptchaPending(page)) {
            return true;
        }
        if (captchaService.hasCapturedPuzzleTask(page)
                && captchaService.isYandexKaleidoscopeChallenge(page)) {
            return true;
        }
        return false;
    }

    /** Видимый чекбокс / advanced в модалке — капча не снята (только пока нет smart-token). */
    private boolean hasVisibleSmartCaptchaUi(Page page) {
        if (captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page)) {
            return true;
        }
        if (captchaService.smartCaptchaTokenLength(page) > 20) {
            return false;
        }
        if (captchaService.isYandexAdvancedUiVisible(page)
                && captchaService.isYandexKaleidoscopeChallenge(page)) {
            return true;
        }
        for (String selector : SMART_CAPTCHA_MARKERS) {
            try {
                Locator loc = page.locator(selector).first();
                if (loc.count() > 0 && loc.isVisible()) {
                    if (selector.contains("advanced-iframe") || selector.contains("backend")) {
                        if (captchaService.smartCaptchaTokenLength(page) > 20
                                && !captchaService.isYandexKaleidoscopeChallenge(page)) {
                            continue;
                        }
                    }
                    return true;
                }
            } catch (Exception ignored) {
                // next selector
            }
        }
        return false;
    }

    /** Реальный challenge (пазл / оверлей / чекбокс), а не пустой iframe после токена. */
    private boolean isActiveYouDoCaptchaChallenge(Page page) {
        int tokenLen = captchaService.smartCaptchaTokenLength(page);
        if (tokenLen > 20) {
            return captchaService.isCheckboxIframeChallenge(page)
                    || captchaService.isYandexCheckboxVisible(page)
                    || captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.isYandexAdvancedUiVisible(page)
                    || captchaService.isSmartCaptchaOverlayVisible(page);
        }
        if (captchaService.isYandexKaleidoscopeChallenge(page)) {
            return true;
        }
        if (captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.isSmartCaptchaOverlayVisible(page)) {
            return true;
        }
        return captchaService.isCheckboxIframeChallenge(page)
                || captchaService.isYandexCheckboxVisible(page);
    }

    private boolean isSmartCaptchaShellInLoginModal(Page page) {
        try {
            Object found = page.evaluate(
                    """
                            () => {
                              const captchaSel = '[data-sentry-component="SmartCaptcha"], [data-sitekey], .smart-captcha, iframe[src*="smartcaptcha"]';
                              const login = document.querySelector('input[name="login"]');
                              const code = document.querySelector('input[name="code"]');
                              if ((login && login.offsetParent !== null) || (code && code.offsetParent !== null)) {
                                return !!document.querySelector(captchaSel);
                              }
                              const buttons = [...document.querySelectorAll('button')].filter(b => b.offsetParent !== null);
                              const dal = buttons.some(b => (b.textContent || '').includes('Далее'));
                              return dal && !!document.querySelector(captchaSel);
                            }
                            """);
            return Boolean.TRUE.equals(found);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSmartCaptchaShellAnywhere(Page page) {
        try {
            Object found = page.evaluate(
                    """
                            () => !!document.querySelector(
                              '[data-sentry-component="SmartCaptcha"], [data-sitekey].smart-captcha, '
                              + 'iframe[src*="smartcaptcha"]');
                            """);
            return Boolean.TRUE.equals(found);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Снимает капчу, которая сейчас блокирует логин.
     *
     * @return {@code true}, если капчи нет или она пройдена до поля OTP / следующего шага формы.
     *         Видимые пазл, advanced и checkbox-iframe не дают выйти раньше времени только потому,
     *         что поле кода уже отрисовано: дополнительная проверка может лечь поверх OTP.
     */
    public boolean passLoginSmartCaptcha(Page page, String login) {
        if (!isLoginCaptchaGate(page)) {
            return true;
        }
        modalLayoutFix.apply(page);
        captchaService.waitForYandexSmartCaptchaWidget(page, 15_000);
        captchaService.revealYouDoLoginCaptchaIframe(page);
        captchaService.revealSmartCaptchaOverlay(page);
        page.waitForTimeout(500);
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (pageClosed(page)) {
                log.warn("YOUDO SmartCaptcha: страница закрыта, останавливаемся, пользователь: {}", login);
                return false;
            }
            if (isOtpFieldVisible(page)
                    && !captchaService.isYandexKaleidoscopeChallenge(page)
                    && !captchaService.isYandexAdvancedUiVisible(page)
                    && !captchaService.isCheckboxIframeChallenge(page)) {
                log.info("YOUDO SmartCaptcha: поле одноразового кода уже на экране — капчу не блокируем, пользователь: {}",
                        login);
                return true;
            }
            if (!isLoginCaptchaGate(page)) {
                return true;
            }
            log.info("YOUDO SmartCaptcha (попытка {}/3), пользователь: {}, url={}",
                    attempt, login, page.url());
            captchaService.logSmartCaptchaState(page, "youdo-login-attempt-" + attempt + "-before");
            if (captchaService.smartCaptchaApiTokenLength(page) > 20
                    && !hasVisibleSmartCaptchaUi(page)
                    && !isActiveYouDoCaptchaChallenge(page)) {
                log.info("YOUDO SmartCaptcha: виджет принял токен (getResponse), challenge нет — пропускаем");
                captchaService.logSmartCaptchaResolutionVerdict(page,
                        "youdo-login-attempt-" + attempt + "-skip-token-in-dom user=" + login);
                trySubmitEmailAfterToken(page, login);
                return true;
            }
            captchaService.waitForYandexSmartCaptchaWidget(page, 12_000);
            if (pageClosed(page)) {
                log.warn("YOUDO SmartCaptcha: страница закрыта, останавливаемся, пользователь: {}", login);
                return false;
            }
            captchaService.revealYouDoLoginCaptchaIframe(page);
            captchaService.revealSmartCaptchaOverlay(page);
            if (pauseOrClosed(page, 500)) {
                return false;
            }

            logDetectedCaptchaUi(page, login, attempt, "перед-solve");
            boolean progressed = solveCurrentChallenge(page, login, attempt);
            log.info("YOUDO SmartCaptcha: solveCurrentChallenge попытка {} → progressed={}, gate={}, stage={}",
                    attempt, progressed, isLoginCaptchaGate(page), isSmartCaptchaStage(page));
            if (!isLoginCaptchaGate(page) && captchaService.isYandexSmartCaptchaAcceptedByWidget(page)) {
                captchaService.dismissSmartCaptchaOverlayIfWidgetAccepted(page);
            } else if (!captchaService.isYandexSmartCaptchaAcceptedByWidget(page)) {
                captchaService.ensureYouDoCaptchaChallengeVisible(page);
            }
            if (pauseOrClosed(page, 800)) {
                return false;
            }
            captchaService.logSmartCaptchaState(page, "youdo-login-attempt-" + attempt + "-after");
            captchaService.logSmartCaptchaResolutionVerdict(page,
                    "youdo-login-attempt-" + attempt + "-after user=" + login);

            if (!isSmartCaptchaStage(page)) {
                if (pauseOrClosed(page, 1200)) {
                    return false;
                }
                if (isLoginCaptchaGate(page)) {
                    log.info("YOUDO SmartCaptcha: после solve снова gate (чекбокс?), попытка {}, пользователь: {}",
                            attempt, login);
                    continue;
                }
                if (!captchaService.isYandexSmartCaptchaReadyForYouDoSubmit(page)) {
                    log.warn(
                            "YOUDO SmartCaptcha: stage снят, но виджет не готов (getResponse/challenge), попытка {}, tokenAPI={}",
                            attempt, captchaService.smartCaptchaApiTokenLength(page));
                    captchaService.ensureYouDoCaptchaChallengeVisible(page);
                    continue;
                }
                captchaService.resumeYandexSmartCaptchaForForm(page);
                captchaService.dismissSmartCaptchaOverlayIfWidgetAccepted(page);
                log.info("YOUDO SmartCaptcha снята после попытки {}, пользователь: {}", attempt, login);
                captchaService.logSmartCaptchaResolutionVerdict(page,
                        "youdo-login-attempt-" + attempt + "-stage-clear user=" + login);
                trySubmitEmailAfterToken(page, login);
                return true;
            }
            if (!progressed) {
                log.warn("YOUDO SmartCaptcha: попытка {} не дала прогресса, пользователь: {}", attempt, login);
            }
        }
        boolean gateOpen = !isLoginCaptchaGate(page);
        captchaService.logSmartCaptchaResolutionVerdict(page,
                "youdo-login-final user=" + login + " gateOpen=" + gateOpen);
        return gateOpen;
    }

    private void logDetectedCaptchaUi(Page page, String login, int attempt, String phase) {
        log.info(
                "YOUDO SmartCaptcha [{}] попытка {} user={}: tokenLen={} loginGate={} smartStage={} | "
                        + "checkbox={} checkboxIframe={} kaleidoscope={} advancedUi={} overlay={} pending={} "
                        + "networkTask={} shellInModal={} twoCaptchaFirst={}",
                phase,
                attempt,
                login,
                captchaService.smartCaptchaTokenLength(page),
                isLoginCaptchaGate(page),
                isSmartCaptchaStage(page),
                captchaService.isYandexCheckboxVisible(page),
                captchaService.isCheckboxIframeChallenge(page),
                captchaService.isYandexKaleidoscopeChallenge(page),
                captchaService.isYandexAdvancedUiVisible(page),
                captchaService.isSmartCaptchaOverlayVisible(page),
                captchaService.isSmartCaptchaPending(page),
                captchaService.hasCapturedPuzzleTask(page),
                isSmartCaptchaShellInLoginModal(page),
                youdoTwoCaptchaFirst);
    }

    private boolean solveCurrentChallenge(Page page, String login, int attempt) {
        CaptchaKind kind = detectCaptchaKind(page);
        YouDoLoginFsmState.log(log,
                kind == CaptchaKind.KALEIDOSCOPE ? YouDoLoginFsmState.CAPTCHA_KALEIDOSCOPE
                        : kind == CaptchaKind.CHECKBOX ? YouDoLoginFsmState.CAPTCHA_CHECKBOX
                        : YouDoLoginFsmState.CAPTCHA_GUARD,
                login,
                "kind=" + kind + " attempt=" + attempt);

        if (isOtpFieldVisible(page)) {
            log.info("YOUDO SmartCaptcha: OTP уже виден до solve (попытка {}) — выходим к вводу кода", attempt);
            return true;
        }

        boolean any = false;

        boolean checkboxUi = captchaService.isYandexCheckboxVisible(page)
                || captchaService.isCheckboxIframeChallenge(page);
        boolean advancedUi = captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.hasCapturedPuzzleTask(page);

        if (kind == CaptchaKind.HIDDEN_SHELL && !checkboxUi && !advancedUi) {
            log.info("YOUDO SmartCaptcha: shell без чекбокса и калейдоскопа — ждём challenge или поле OTP, попытка {}",
                    attempt);
            if (!waitForChallengeOrOtp(page, 12_000)) {
                log.warn("YOUDO SmartCaptcha: shell так и не стал капчей/OTP — YandexSmartCaptchaTask не шлём");
                return false;
            }
            if (isOtpFieldVisible(page)) {
                log.info("YOUDO SmartCaptcha: пока ждали shell, появилось поле OTP");
                return true;
            }
            kind = detectCaptchaKind(page);
            checkboxUi = captchaService.isYandexCheckboxVisible(page)
                    || captchaService.isCheckboxIframeChallenge(page);
            advancedUi = captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.isYandexAdvancedUiVisible(page)
                    || captchaService.hasCapturedPuzzleTask(page);
            log.info("YOUDO SmartCaptcha: после ожидания shell kind={} checkboxUi={} advancedUi={}",
                    kind, checkboxUi, advancedUi);
        }

        log.info(
                "YOUDO SmartCaptcha → ветвление (попытка {}): kind={} checkboxUi={} advancedUi={} shouldAdvanced2Captcha={}",
                attempt, kind, checkboxUi, advancedUi, shouldSolveAdvancedViaTwoCaptcha(page));

        if (youdoTwoCaptchaFirst && captchaService.isTwoCaptchaConfigured()) {
            YouDoLoginFsmState.log(log, YouDoLoginFsmState.CAPTCHA_TWO_CAPTCHA, login,
                    "только 2Captcha, attempt=" + attempt);
            boolean twoCaptchaAll = captchaService.solveYandexForYouDoViaTwoCaptcha(page);
            log.info("YOUDO SmartCaptcha → solveYandexForYouDoViaTwoCaptcha → {}", twoCaptchaAll ? "ok" : "не снято");
            any |= twoCaptchaAll;
            if (!isSmartCaptchaStage(page) && !isLoginCaptchaGate(page)) {
                log.info("YOUDO SmartCaptcha → после 2Captcha gate снят, выход");
                return any;
            }
            if (advancedUi || kind == CaptchaKind.KALEIDOSCOPE) {
                boolean adv = captchaService.solveYandexAdvancedViaTwoCaptcha(page);
                log.info("YOUDO SmartCaptcha → solveYandexAdvancedViaTwoCaptcha → {}", adv ? "ok" : "не снято");
                any |= adv;
            }
            if (!isSmartCaptchaStage(page) && !isLoginCaptchaGate(page)) {
                return any;
            }
            log.warn("YOUDO SmartCaptcha: 2Captcha не снял капчу (без локального клика), attempt={}", attempt);
            return any;
        }
        if (youdoTwoCaptchaFirst) {
            log.warn("YOUDO SmartCaptcha: twoCaptchaFirst=true, но 2Captcha не настроен");
            return false;
        }

        if (checkboxUi && !youdoTwoCaptchaFirst) {
            log.info("YOUDO SmartCaptcha: сначала клик «Я не робот» (checkbox-iframe), попытка {}, пользователь: {}",
                    attempt, login);
            if (solveCheckbox(page, login, attempt, "early-checkbox-click")) {
                return true;
            }
            if (kaleidoscopeReady(page) || captchaService.isYandexAdvancedUiVisible(page)) {
                log.info("YOUDO SmartCaptcha: после чекбокса открылся следующий challenge — решаем его, токен не ждём");
            }
        }

        if (captchaService.smartCaptchaTokenLength(page) > 20 && checkboxUi) {
            log.info("YOUDO SmartCaptcha: токен в DOM, добиваем чекбокс (попытка {}), пользователь: {}",
                    attempt, login);
            if (solveCheckbox(page, login, attempt, "token-plus-checkbox")) {
                return true;
            }
        }

        // Красный «Я не робот» в модалке: сначала чекбокс; task из /check не значит пазл.
        if (checkboxUi && !advancedUi && !kaleidoscopeReady(page)) {
            log.info("YOUDO SmartCaptcha: этап чекбокс (попытка {}), пользователь: {}", attempt, login);
            any |= solveCheckbox(page, login, attempt, "checkbox-only");
        }
        if (!isSmartCaptchaStage(page)) {
            log.info("YOUDO SmartCaptcha → после чекбокса smartStage=false");
            return any;
        }

        boolean kaleidoscope = captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.hasCapturedPuzzleTask(page);
        if (kaleidoscope) {
            log.info("YOUDO SmartCaptcha: калейдоскоп — Pazl, клики по слайдеру (попытка {}), пользователь: {}",
                    attempt, login);
            boolean pazlOk = captchaService.solveYouDoBrowserSessionKaleidoscope(page);
            log.info("YOUDO SmartCaptcha → Pazl слайдер → {}", pazlOk ? "ok" : "не снято");
            any |= pazlOk;
            if (pageClosed(page)) {
                return any;
            }
            if (captchaService.isYandexKaleidoscopeChallenge(page)) {
                log.info("YOUDO SmartCaptcha: после Pazl калейдоскоп ещё на экране — дополнительная проверка, решаем следующий пазл");
                boolean again = captchaService.solveYouDoBrowserSessionKaleidoscope(page);
                log.info("YOUDO SmartCaptcha → Pazl доп. проверка → {}", again ? "ok" : "не снято");
                any |= again;
            }
            if (pageClosed(page) || !isSmartCaptchaStage(page)) {
                return any;
            }
            if (captchaService.isYandexKaleidoscopeChallenge(page)) {
                log.warn("YOUDO SmartCaptcha: пазл ещё на экране — token и audio не запускаем, попытка {}", attempt);
                return any;
            }
        }
        if ((shouldSolveAdvancedViaTwoCaptcha(page) || advancedUi) && !kaleidoscope) {
            log.info("YOUDO SmartCaptcha: этап advanced 2Captcha token+audio (попытка {}), networkTask={} kaleidoscope=false",
                    attempt, captchaService.hasCapturedPuzzleTask(page));
            boolean advancedOk = captchaService.solveYandexAdvancedViaTwoCaptcha(page);
            log.info("YOUDO SmartCaptcha → solveYandexAdvancedViaTwoCaptcha → {}", advancedOk ? "ok" : "не снято");
            any |= advancedOk;
        }
        if (!isSmartCaptchaStage(page)) {
            log.info("YOUDO SmartCaptcha → после advanced 2Captcha smartStage=false");
            return any;
        }

        if (!advancedUi && checkboxUi) {
            log.info("YOUDO SmartCaptcha → повтор чекбокса после advanced (попытка {})", attempt);
            any |= solveCheckbox(page, login, attempt, "checkbox-after-advanced");
        }

        if (!isSmartCaptchaStage(page)) {
            return any;
        }

        if (shouldSolveAdvancedViaTwoCaptcha(page)) {
            log.info("YOUDO SmartCaptcha → повтор advanced 2Captcha (попытка {})", attempt);
            boolean retryAdvanced = captchaService.solveYandexAdvancedViaTwoCaptcha(page);
            log.info("YOUDO SmartCaptcha → повтор solveYandexAdvancedViaTwoCaptcha → {}",
                    retryAdvanced ? "ok" : "не снято");
            any |= retryAdvanced;
        } else if (isSmartCaptchaStage(page)) {
            log.info("YOUDO SmartCaptcha: локальный клик виджета (попытка {}), пользователь: {}", attempt, login);
            boolean localWidget = captchaService.solveYandexSmartCaptcha(page);
            log.info("YOUDO SmartCaptcha → solveYandexSmartCaptcha (локально) → {}",
                    localWidget ? "ok" : "не снято");
            any |= localWidget;
            if (!any && yandexTwoCaptchaFallback && captchaService.isTwoCaptchaConfigured()) {
                log.info("YOUDO SmartCaptcha: fallback YandexSmartCaptchaTask (попытка {}), пользователь: {}",
                        attempt, login);
                boolean tokenFallback = captchaService.solveYandexSmartCaptchaViaToken(page);
                log.info("YOUDO SmartCaptcha → solveYandexSmartCaptchaViaToken → {}",
                        tokenFallback ? "ok" : "не снято");
                any |= tokenFallback;
            }
        }
        logDetectedCaptchaUi(page, login, attempt, "после-solve");
        return any;
    }

    /** Поле кода из письма уже на экране — капча не должна отменять ввод OTP. */
    public boolean isOtpFieldVisible(Page page) {
        if (page == null || page.isClosed()) {
            return false;
        }
        try {
            Locator code = page.locator(
                    "input[name='code'], input[autocomplete='one-time-code']").first();
            return code.count() > 0 && code.isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForChallengeOrOtp(Page page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (page == null || page.isClosed()) {
                return false;
            }
            if (isOtpFieldVisible(page)) {
                return true;
            }
            if (captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.isYandexAdvancedUiVisible(page)
                    || captchaService.hasCapturedPuzzleTask(page)
                    || captchaService.isYandexCheckboxVisible(page)
                    || captchaService.isCheckboxIframeChallenge(page)) {
                return true;
            }
            page.waitForTimeout(400);
        }
        return isOtpFieldVisible(page);
    }

    private void trySubmitEmailAfterToken(Page page, String login) {
        if (page == null || page.isClosed()) {
            return;
        }
        if (!captchaService.isYandexSmartCaptchaReadyForYouDoSubmit(page)) {
            log.info("YOUDO post-token: пропуск submit — getResponse/challenge не готовы, tokenAPI={}",
                    captchaService.smartCaptchaApiTokenLength(page));
            return;
        }
        try {
            Locator email = page.locator("input[name='login']").first();
            if (email.count() == 0 || !email.isVisible()) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        postCaptchaSubmit.submitEmailAfterSmartToken(page, login);
    }

    private boolean solveCheckbox(Page page, String login, int attempt, String reason) {
        if (pageClosed(page)) {
            log.warn("YOUDO SmartCaptcha → checkbox [{}]: страница закрыта, останавливаемся", reason);
            return false;
        }
        log.info("YOUDO SmartCaptcha → checkbox [{}] попытка {} user={}", reason, attempt, login);
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                && !captchaService.isCheckboxIframeChallenge(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: пропуск (калейдоскоп без checkbox-iframe)", reason);
            return false;
        }
        captchaService.clearLastFailure();
        if (youdoTwoCaptchaFirst && captchaService.isTwoCaptchaConfigured()) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: 2Captcha для checkbox", reason);
            if (captchaService.solveYandexForYouDoViaTwoCaptcha(page)) {
                page.waitForTimeout(1200);
                if (!isSmartCaptchaStage(page)
                        && captchaService.isYandexSmartCaptchaReadyForYouDoSubmit(page)) {
                    log.info("YOUDO SmartCaptcha → checkbox [{}]: 2Captcha ok, resumeYandexSmartCaptchaForForm",
                            reason);
                    captchaService.resumeYandexSmartCaptchaForForm(page);
                    return true;
                }
            }
            log.info("YOUDO SmartCaptcha → checkbox [{}]: 2Captcha не завершил checkbox", reason);
        }
        if (kaleidoscopeReady(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: калейдоскоп уже на экране, чекбокс не кликаем", reason);
            return false;
        }
        log.info("YOUDO SmartCaptcha → checkbox [{}]: клик в checkbox-iframe", reason);
        boolean clicked = captchaService.clickCheckboxInSmartCaptchaIframe(page);
        if (!captchaService.isYandexSmartCaptchaReadyForYouDoSubmit(page)
                && (kaleidoscopeReady(page) || awaitKaleidoscopeAfterCheckbox(page, 4_000))) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: следом открылся калейдоскоп — Pazl, токен не шлём",
                    reason);
            return false;
        }
        if (clicked) {
            if (pauseOrClosed(page, 400)) {
                return false;
            }
            if (captchaService.isYandexSmartCaptchaReadyForYouDoSubmit(page)) {
                log.info("YOUDO SmartCaptcha → checkbox [{}]: клик ok, getResponse OK, resume формы", reason);
                captchaService.resumeYandexSmartCaptchaForForm(page);
                return true;
            }
            if (!isSmartCaptchaStage(page)) {
                log.info("YOUDO SmartCaptcha → checkbox [{}]: клик ok, smartStage=false", reason);
                return true;
            }
            log.info("YOUDO SmartCaptcha → checkbox [{}]: клик выполнен, капча ещё на экране", reason);
        } else {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: клик в iframe не удался", reason);
        }
        if (pageClosed(page)) {
            log.warn("YOUDO SmartCaptcha → checkbox [{}]: страница закрыта после клика, останавливаемся", reason);
            return false;
        }
        if (kaleidoscopeReady(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: калейдоскоп открыт, solveYandexSmartCaptcha не зовём", reason);
            return false;
        }
        if (kaleidoscopeReady(page) || captchaService.isYandexAdvancedUiVisible(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: перед локальным solve уже калейдоскоп — токен не шлём",
                    reason);
            return false;
        }
        log.info("YOUDO SmartCaptcha → checkbox [{}]: solveYandexSmartCaptcha (локально)", reason);
        if (captchaService.solveYandexSmartCaptcha(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: локальный виджет ok", reason);
            return true;
        }
        if (kaleidoscopeReady(page) || captchaService.isYandexAdvancedUiVisible(page)) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: локальный клик открыл калейдоскоп — токен не шлём",
                    reason);
            return false;
        }
        if (yandexTwoCaptchaFallback && captchaService.isTwoCaptchaConfigured()) {
            log.info("YOUDO SmartCaptcha → checkbox [{}]: fallback token 2Captcha", reason);
            boolean tokenOk = captchaService.solveYandexSmartCaptchaViaToken(page);
            log.info("YOUDO SmartCaptcha → checkbox [{}]: solveYandexSmartCaptchaViaToken → {}",
                    reason, tokenOk ? "ok" : "не снято");
            return tokenOk;
        }
        captchaService.recordSmartCaptchaFailure(page, CaptchaFailureCode.YOUDO_KALEIDOSCOPE_FAIL,
                "YouDo SmartCaptcha: не пройден чекбокс", "youdo-checkbox");
        log.warn("YOUDO SmartCaptcha → checkbox [{}]: все способы исчерпаны", reason);
        return false;
    }

    private boolean kaleidoscopeReady(Page page) {
        try {
            return captchaService.isYandexKaleidoscopeChallenge(page)
                    || captchaService.hasCapturedPuzzleTask(page);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Чекбокс «Я не робот» часто сразу открывает следующий кадр. Ждём его, чтобы не уйти в токен
     * поверх уже видимого пазла.
     */
    private boolean awaitKaleidoscopeAfterCheckbox(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pageClosed(page)) {
                return false;
            }
            if (kaleidoscopeReady(page)) {
                return true;
            }
            if (pauseOrClosed(page, 250)) {
                return false;
            }
        }
        return kaleidoscopeReady(page);
    }

    private static boolean pageClosed(Page page) {
        try {
            return page == null || page.isClosed();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** {@code true}, если страница закрыта и цикл капчи надо остановить. */
    private boolean pauseOrClosed(Page page, int ms) {
        if (pageClosed(page)) {
            log.warn("YOUDO SmartCaptcha: страница закрыта, останавливаемся");
            return true;
        }
        try {
            page.waitForTimeout(ms);
        } catch (RuntimeException e) {
            if (pageClosed(page) || (e.getMessage() != null && e.getMessage().contains("closed"))) {
                log.warn("YOUDO SmartCaptcha: страница закрыта во время ожидания");
                return true;
            }
            throw e;
        }
        return pageClosed(page);
    }

    /** Advanced: калейдоскоп / overlay / task из /check — решаем token+audio через 2Captcha. */
    public boolean shouldSolveAdvancedViaTwoCaptcha(Page page) {
        if (captchaService.isYandexKaleidoscopeChallenge(page)
                || captchaService.isYandexAdvancedUiVisible(page)
                || captchaService.isSmartCaptchaOverlayVisible(page)) {
            return true;
        }
        if (captchaService.isCheckboxIframeChallenge(page)
                || captchaService.isYandexCheckboxVisible(page)) {
            return false;
        }
        return captchaService.hasCapturedPuzzleTask(page)
                && captchaService.isYandexKaleidoscopeChallenge(page);
    }
}
