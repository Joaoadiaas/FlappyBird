package FlappyBird;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class FlappyParallel {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(GameFrame::new);
    }
}

class GameFrame extends JFrame {
    GameFrame() {
        super("FlappyParallel — Threads & Concorrência (com Quiz)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        GamePanel panel = new GamePanel(900, 540);
        setContentPane(panel);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
        panel.start();
    }
}

class GamePanel extends JPanel implements KeyListener {
    // Tela
    private final int W, H;

    // Bird
    private int birdX = 140;
    private int birdY = 240;
    private double birdVel = 0;
    private double gravity = 0.55;
    private double jumpPower = -9.8;

    // Pipes
    private static class Pipe {
        int x; int lastX; int gapY; int width = 70;
        int gapH; int speed; boolean scored = false;
        Rectangle topRect() { return new Rectangle(x, 0, width, Math.max(0, gapY - gapH/2)); }
        Rectangle botRect(int H) { return new Rectangle(x, gapY + gapH/2, width, Math.max(0, H - (gapY + gapH/2))); }
    }
    private final CopyOnWriteArrayList<Pipe> pipes = new CopyOnWriteArrayList<>();

    // Threads
    private Thread gameLoop;
    private ScheduledThreadPoolExecutor sched;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean gameOver = new AtomicBoolean(false);

    // FPS & Tick
    private final AtomicLong frameCount = new AtomicLong(0);
    private double lastFps = 0.0;
    private long lastFpsTime = System.nanoTime();

    // Tempo lógico
    private long gameMillis = 0L;

    // Pontos
    private int score = 0;
    private Long lastScoreAtMs = null;
    private final Random rng = new Random();

    // Regras de geração
    private int GAP_H = 200;
    private static final int GAP_MIN = 168;
    private static final int MARGIN_MIN = 60;
    private static final int SPACING_MIN = 260;
    private static final int SPACING_MAX = 340;

    // QUIZ -----------
    private static class Question {
        final String text; final String[] opts; final int correct; // 0..3
        Question(String t, String[] o, int c){ text=t; opts=o; correct=c; }
    }
    private final Question[] questionBank = new Question[]{
        new Question("O que é um processo em um sistema operacional?",
            new String[]{"Um arquivo armazenado em disco",
                         "Um programa em execução com seu próprio espaço de endereçamento",
                         "Uma thread dentro de um programa",
                         "Um serviço de rede"}, 1),
        new Question("Qual afirmação diferencia corretamente processos de threads?",
            new String[]{"Threads têm espaço de endereçamento próprio e processos compartilham memória",
                         "Processos e threads sempre compartilham o mesmo espaço de endereçamento",
                         "Threads compartilham o espaço de endereçamento do processo onde existem",
                         "Processos não podem executar em paralelo"}, 2),
        new Question("Um benefício comum do uso de threads é:",
            new String[]{"Comutação de contexto mais lenta que entre processos",
                         "Comunicação mais rápida entre unidades por compartilharem memória",
                         "Necessitar mais memória do que processos",
                         "Impedir paralelismo"}, 1),
        new Question("Em Java, para iniciar a execução concorrente de uma thread criada, deve-se chamar:",
            new String[]{"run()", "execute()", "start()", "init()"}, 2),
        new Question("Uma thread que está aguardando término de E/S está, tipicamente, no estado:",
            new String[]{"Running", "Runnable", "Not Runnable/Bloqueada", "Dead, mas revivível"}, 2),
        new Question("Uma condição de corrida ocorre quando:",
            new String[]{"Duas threads possuem prioridades iguais",
                         "Duas threads acessam dados compartilhados sem sincronização e o resultado depende da interleaving",
                         "O SO impede duas threads de executarem ao mesmo tempo",
                         "Há apenas uma CPU disponível"}, 1),
        new Question("O objetivo da exclusão mútua em regiões críticas é:",
            new String[]{"Aumentar a prioridade das threads",
                         "Garantir que várias threads atualizem o mesmo dado simultaneamente",
                         "Garantir que, por vez, apenas uma thread acesse o recurso compartilhado",
                         "Reduzir o uso de memória do processo"}, 2),
        new Question("Em Java, o modificador volatile serve principalmente para:",
            new String[]{"Tornar operações como i++ atômicas",
                         "Prevenir deadlocks",
                         "Garantir visibilidade de memória entre threads",
                         "Forçar a thread a dormir após escrita"}, 2),
        new Question("Sobre escalonamento de threads, é correto afirmar que:",
            new String[]{"Apenas threads no estado Runnable podem ser escolhidas para executar",
                         "Threads em estado Dead são escalonadas com baixa prioridade",
                         "O escalonador só escolhe threads com prioridade máxima",
                         "Threads bloqueadas são sempre executadas primeiro"}, 0),
        new Question("Na Taxonomia de Flynn, múltiplas instruções sobre múltiplos dados é:",
            new String[]{"SISD","SIMD","MISD","MIMD"}, 3)
    };
    private final AtomicBoolean inQuiz = new AtomicBoolean(false);
    private Question currentQ = null;
    private String[] currentOpts = null; // alternativas embaralhadas
    private int correctIdx = -1;         // índice correto após embaralhar
    private int nextQuizAt = 5;          // a cada 5 pontos

    // Fonts
    private final Font fBig = new Font("Segoe UI", Font.BOLD, 24);
    private final Font fSmall = new Font("Segoe UI", Font.PLAIN, 14);
    private final Font fTitle = new Font("Segoe UI", Font.BOLD, 28);

    GamePanel(int width, int height) {
        this.W = width; this.H = height;
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addKeyListener(this);
        setBackground(new Color(8,10,24));
    }

    void start() {
        if (running.get()) return;

        // Reset
        running.set(true);
        gameOver.set(false);
        pipes.clear();
        score = 0;
        lastScoreAtMs = null;
        birdX = 140; birdY = H/2; birdVel = 0;
        GAP_H = 200;
        gameMillis = 0L;

        // Quiz reset
        inQuiz.set(false);
        currentQ = null; currentOpts = null; correctIdx = -1;
        nextQuizAt = 5;

        // Loop do jogo
        gameLoop = new Thread(this::loop, "GameLoop");
        gameLoop.start();

        // Pool agendador
        if (sched == null || sched.isShutdown()) {
            sched = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(2);
        }

        // Spawn de canos
        sched.scheduleWithFixedDelay(this::trySpawnPipe, 0, 120, TimeUnit.MILLISECONDS);

        // Dificuldade progressiva
        sched.scheduleAtFixedRate(() -> {
            if (!running.get() || gameOver.get() || inQuiz.get()) return;
            GAP_H = Math.max(GAP_MIN, GAP_H - 4);
        }, 7, 7, TimeUnit.SECONDS);
    }

    private void stop() {
        running.set(false);
        if (sched != null) sched.shutdownNow();
        try { if (gameLoop != null) gameLoop.join(200); } catch (InterruptedException ignored) {}
    }

    private void loop() {
        long prev = System.nanoTime();
        while (running.get()) {
            long now = System.nanoTime();
            double dtMs = (now - prev) / 1_000_000.0;
            prev = now;
            gameMillis += (long) dtMs;

            if (!gameOver.get()) update(dtMs);

            repaint();
            throttleFps(60);
            computeFps();
        }
    }

    private void throttleFps(int target) {
        try { Thread.sleep(Math.max(0, 1000/target)); } catch (InterruptedException ignored) {}
    }

    private void computeFps() {
        frameCount.incrementAndGet();
        long now = System.nanoTime();
        if ((now - lastFpsTime) >= 1_000_000_000L) {
            lastFps = frameCount.getAndSet(0);
            lastFpsTime = now;
        }
    }

    // ---------- Lógica principal ----------
    private void update(double dtMs) {
        // Pausa total durante o quiz
        if (inQuiz.get()) return;

        birdVel += gravity;
        birdY += (int) Math.round(birdVel);

        if (birdY < 0) { birdY = 0; birdVel = 0; }
        if (birdY > H-40) { birdY = H-40; gameOver.set(true); }

        final int birdRefX = birdX + 17;

        for (Pipe pipe : pipes) {
            pipe.lastX = pipe.x;

            int dx = (int) Math.round(pipe.speed * (dtMs / 16.67));
            if (dx < 1) dx = 1;
            pipe.x -= dx;

            if (pipe.x + pipe.width < 0) { pipes.remove(pipe); continue; }

            if (collides(pipe)) gameOver.set(true);

            // Pontuação robusta
            int edgeBefore = pipe.lastX + pipe.width;
            int edgeNow    = pipe.x + pipe.width;
            if (!pipe.scored && edgeBefore >= birdRefX && edgeNow < birdRefX) {
                score++;
                pipe.scored = true;
                lastScoreAtMs = gameMillis;

                // Checkpoint de quiz
                if (score >= nextQuizAt && !inQuiz.get()) {
                    triggerQuiz();
                }
            }
        }
    }

    private boolean collides(Pipe p) {
        Rectangle bird = new Rectangle(birdX, birdY, 34, 24);
        return bird.intersects(p.topRect()) || bird.intersects(p.botRect(H));
    }

    private void trySpawnPipe() {
        if (!running.get() || gameOver.get() || inQuiz.get()) return;

        if (!pipes.isEmpty()) {
            Pipe last = pipes.get(pipes.size() - 1);
            int distToRight = W - (last.x + last.width);
            if (distToRight < SPACING_MIN) return;
        }

        int half = GAP_H / 2;
        int minCenter = MARGIN_MIN + half;
        int maxCenter = (H - MARGIN_MIN) - half;
        int gapCenter = rng.nextInt(Math.max(1, maxCenter - minCenter + 1)) + minCenter;

        Pipe p = new Pipe();
        p.x = W + rng.nextInt(30) + 20;
        p.gapY = gapCenter;
        p.gapH = GAP_H;
        p.speed = 3 + rng.nextInt(2);

        if (!pipes.isEmpty()) {
            Pipe last = pipes.get(pipes.size() - 1);
            int spacing = p.x - (last.x + last.width);
            if (spacing < SPACING_MIN) return;
            if (spacing > SPACING_MAX) p.x = last.x + last.width + SPACING_MAX;
        }

        pipes.add(p);
    }

    // ---------- Quiz ----------
    private void triggerQuiz() {
        currentQ = questionBank[rng.nextInt(questionBank.length)];

        // Embaralhar as alternativas mantendo o mapeamento da correta
        String[] orig = currentQ.opts;
        int correctOriginal = currentQ.correct;
        currentOpts = new String[4];
        int[] idx = {0,1,2,3};
        // Fisher-Yates
        for (int i = idx.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
        }
        for (int i = 0; i < 4; i++) currentOpts[i] = orig[idx[i]];
        for (int i = 0; i < 4; i++) if (idx[i] == correctOriginal) { correctIdx = i; break; }

        inQuiz.set(true);
    }

    private void resolveQuizAnswer(int chosenIdx) {
        if (!inQuiz.get()) return;
        if (chosenIdx == correctIdx) {
            // Acertou → continua e agenda próximo checkpoint
            nextQuizAt += 5;
            currentQ = null; currentOpts = null; correctIdx = -1;
            inQuiz.set(false);
        } else {
            // Errou - game over
            gameOver.set(true);
            currentQ = null; currentOpts = null; correctIdx = -1;
            inQuiz.set(false);
        }
    }

    // ---------- Entrada ----------
    @Override public void keyPressed(KeyEvent e) {
        if (!running.get()) return;

        if (gameOver.get()) {
            if (e.getKeyCode() == KeyEvent.VK_R) restart();
            return;
        }

        if (inQuiz.get()) {
            int kc = e.getKeyCode();
            if (kc == KeyEvent.VK_1 || kc == KeyEvent.VK_NUMPAD1 || kc == KeyEvent.VK_A) resolveQuizAnswer(0);
            else if (kc == KeyEvent.VK_2 || kc == KeyEvent.VK_NUMPAD2 || kc == KeyEvent.VK_B) resolveQuizAnswer(1);
            else if (kc == KeyEvent.VK_3 || kc == KeyEvent.VK_NUMPAD3 || kc == KeyEvent.VK_C) resolveQuizAnswer(2);
            else if (kc == KeyEvent.VK_4 || kc == KeyEvent.VK_NUMPAD4 || kc == KeyEvent.VK_D) resolveQuizAnswer(3);
            return;
        }

        if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_W || e.getKeyCode() == KeyEvent.VK_UP) {
            birdVel = jumpPower;
        }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    private void restart() {
        stop();
        start();
    }

    // ---------- Desenho ----------
    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        paintBg(g2);
        for (Pipe p : pipes) paintPipe(g2, p);
        paintBird(g2);

        // HUD
        g2.setFont(fBig);
        g2.setColor(Color.WHITE);
        g2.drawString("Score: " + score, 20, 40);
        g2.setFont(fSmall);
        int poolSize = (sched != null) ? sched.getPoolSize() : 0;
        g2.drawString(String.format("FPS: %.0f | Pool: %d threads", lastFps, poolSize), 20, 60);
        g2.drawString("Controles: Espaço/W/↑ p/ pular — R reinicia", 20, 80);
        String lastScoreStr = (lastScoreAtMs == null) ? "—" : lastScoreAtMs + " ms";
        g2.drawString("Último ponto em t= " + lastScoreStr, 20, 100);

        if (inQuiz.get()) {
            paintQuizOverlay(g2);
        }

        if (gameOver.get() && !inQuiz.get()) {
            g2.setFont(fTitle);
            drawCentered(g2, "GAME OVER — tecle R para reiniciar", H/2);
        }
    }

    private void paintBg(Graphics2D g2) {
        GradientPaint gp = new GradientPaint(0,0,new Color(12,18,48),0,H,new Color(20,28,70));
        g2.setPaint(gp);
        g2.fillRect(0,0,W,H);
        g2.setColor(new Color(40,45,80));
        g2.fillRect(0, H-40, W, 40);
    }

    private void paintPipe(Graphics2D g2, Pipe p) {
        g2.setColor(new Color(90, 210, 140));
        Rectangle top = p.topRect();
        Rectangle bot = p.botRect(H);
        g2.fillRect(top.x, top.y, top.width, top.height);
        g2.fillRect(bot.x, bot.y, bot.width, bot.height);
        g2.setColor(new Color(60,150,100));
        g2.drawRect(top.x, top.y, top.width, top.height);
        g2.drawRect(bot.x, bot.y, bot.width, bot.height);
    }

    private void paintBird(Graphics2D g2) {
        int bw = 34, bh = 24;
        g2.setColor(new Color(250,220,90));
        g2.fillRoundRect(birdX, birdY, bw, bh, 10,10);
        g2.setColor(Color.BLACK);
        g2.drawRoundRect(birdX, birdY, bw, bh, 10,10);
        g2.fillOval(birdX + 22, birdY + 6, 6, 6);
        g2.setColor(new Color(255,140,60));
        g2.fillPolygon(new int[]{birdX + bw, birdX + bw + 10, birdX + bw},
                       new int[]{birdY + 8, birdY + 12, birdY + 16}, 3);
    }

    private void paintQuizOverlay(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, W, H);

        int cardW = (int) (W * 0.8);
        int cardH = Math.min((int) (H * 0.85), H - 40);
        int cx = (W - cardW) / 2;
        int cy = (H - cardH) / 2;

        g2.setColor(new Color(246, 247, 255));
        g2.fillRoundRect(cx, cy, cardW, cardH, 24, 24);
        Stroke previousStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(60, 68, 128));
        g2.drawRoundRect(cx, cy, cardW, cardH, 24, 24);
        g2.setStroke(previousStroke);

        int padding = 32;
        int columnGap = 18;
        int optHeight = 70;
        int optGapY = 16;
        int instructionY = cy + cardH - padding - 12;
        int maxOptionsBottom = instructionY - 36;
        int optionsAreaHeight = optHeight * 2 + optGapY;
        int titleY = cy + padding + 30;

        g2.setFont(fTitle);
        g2.setColor(new Color(26, 32, 58));
        drawCentered(g2, "Checkpoint: responda para continuar!", titleY);

        int questionTop = titleY + 26;
        int questionDesired = Math.max(120, (int) (cardH * 0.32));
        int questionMaxHeight = maxOptionsBottom - optionsAreaHeight - questionTop - 24;
        int questionBoxHeight;
        if (questionMaxHeight <= 0) {
            questionBoxHeight = 0;
        } else if (questionMaxHeight < 110) {
            questionBoxHeight = questionMaxHeight;
        } else {
            questionBoxHeight = Math.max(110, Math.min(questionDesired, questionMaxHeight));
        }

        int questionBoxX = cx + padding;
        int questionBoxW = cardW - padding * 2;
        g2.setColor(new Color(230, 234, 255));
        g2.fillRoundRect(questionBoxX, questionTop, questionBoxW, questionBoxHeight, 18, 18);
        g2.setColor(new Color(100, 112, 170));
        g2.drawRoundRect(questionBoxX, questionTop, questionBoxW, questionBoxHeight, 18, 18);

        g2.setFont(fBig);
        g2.setColor(new Color(26, 32, 58));
        String questionText = (currentQ != null) ? currentQ.text : "";
        drawWrapped(g2, questionText, questionBoxX + 22, questionTop + 36, questionBoxW - 44, 26);

        int optionsTop = questionTop + questionBoxHeight + 24;
        if (optionsTop + optionsAreaHeight > maxOptionsBottom) {
            optionsTop = Math.max(questionTop + 24, maxOptionsBottom - optionsAreaHeight);
        }

        int optionsLeft = questionBoxX;
        int optionsWidth = questionBoxW;
        int columnWidth = (optionsWidth - columnGap) / 2;
        Font optLabelFont = fSmall.deriveFont(Font.BOLD, 18f);
        Font optTextFont = fSmall.deriveFont(16f);
        String[] labels = {"[1] A", "[2] B", "[3] C", "[4] D"};
        for (int i = 0; i < 4; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = optionsLeft + col * (columnWidth + columnGap);
            int y = optionsTop + row * (optHeight + optGapY);
            String optionText = (currentOpts != null) ? currentOpts[i] : "";
            drawOptionCard(g2, labels[i], optionText, x, y, columnWidth, optHeight, optLabelFont, optTextFont);
        }

        g2.setFont(fSmall.deriveFont(Font.PLAIN, 16f));
        g2.setColor(new Color(74, 82, 122));
        drawCentered(g2, "Use 1-4 ou A-D para responder. Errou = game over.", instructionY);
    }

    private void drawOptionCard(Graphics2D g2, String label, String text, int x, int y, int width, int height, Font labelFont, Font textFont) {
        int arc = 18;
        int innerPad = 18;
        g2.setColor(new Color(244, 245, 255));
        g2.fillRoundRect(x, y, width, height, arc, arc);
        g2.setColor(new Color(162, 172, 210));
        g2.drawRoundRect(x, y, width, height, arc, arc);

        g2.setFont(labelFont);
        g2.setColor(new Color(46, 52, 94));
        int labelBaseline = y + 26;
        g2.drawString(label, x + innerPad, labelBaseline);

        g2.setFont(textFont);
        g2.setColor(new Color(58, 64, 102));
        drawWrapped(g2, text, x + innerPad, labelBaseline + 20, width - innerPad * 2, 20);
    }

    private void drawCentered(Graphics2D g2, String msg, int y) {
        FontMetrics fm = g2.getFontMetrics();
        int x = (W - fm.stringWidth(msg)) / 2;
        g2.drawString(msg, x, y);
    }

    private void drawWrapped(Graphics2D g2, String text, int x, int y, int maxW, int lineH) {
        FontMetrics fm = g2.getFontMetrics();
        String[] words = text.split(" ");
        String line = "";
        int cy = y;
        for (String w : words) {
            String test = line.isEmpty() ? w : line + " " + w;
            if (fm.stringWidth(test) > maxW) {
                g2.drawString(line, x, cy);
                cy += lineH;
                line = w;
            } else line = test;
        }
        if (!line.isEmpty()) g2.drawString(line, x, cy);
    }
}
