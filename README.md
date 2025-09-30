# FlappyParallel

## Descrição
FlappyParallel é uma releitura do clássico Flappy Bird criada para explorar conceitos de programação paralela. O jogador controla um pássaro em ritmo acelerado, enquanto checkpoints exibem perguntas sobre threads, processos e concorrência que precisam ser respondidas para continuar avançando.

## Tecnologias utilizadas
- Java com Swing/AWT para a renderização 2D e a interface gráfica do quiz
- java.util.concurrent (ScheduledThreadPoolExecutor, CopyOnWriteArrayList) para organizar atualizações e geração de obstáculos em múltiplas threads
- Estruturas atômicas (AtomicBoolean, AtomicLong) garantindo sincronização segura entre threads do jogo

## Objetivo do jogo
Manter o pássaro voando o maior tempo possível, atravessando os canos sem colidir, pontuando e respondendo corretamente às questões de concorrência. O jogo busca fixar os conceitos de paralelismo de forma lúdica, penalizando respostas erradas com o fim da partida.
