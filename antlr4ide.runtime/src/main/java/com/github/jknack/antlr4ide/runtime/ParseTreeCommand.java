package com.github.jknack.antlr4ide.runtime;

import java.io.File;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

import org.antlr.v4.Tool;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.ParserInterpreter;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.antlr.v4.runtime.misc.Utils;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarRootAST;

public class ParseTreeCommand {

  private PrintWriter out;

  public ParseTreeCommand(final PrintWriter out) {
    this.out = out;
  }

  public String run(final String grammarFileName, final String lexerFileName,
      final String outdir, final List<String> imports, final String startRule,
      final String inputText) {
    Tool antlr = new Tool();
    if (imports.size() > 0) {
      antlr.inputDirectory = new File(imports.iterator().next()).getParentFile();
    }
    antlr.libDirectory = outdir;

    // load to examine it
    Grammar g = loadGrammar(antlr, grammarFileName, null);

    final LexerGrammar lg;
    if (g instanceof LexerGrammar) {
      lg = (LexerGrammar) g;
    } else if (lexerFileName != null) {
      lg = (LexerGrammar) antlr.loadGrammar(lexerFileName);
      g = loadGrammar(antlr, grammarFileName, lg);
    } else {
      // combined?
      lg = g.getImplicitLexer();
    }

    final String gname = g.name;
    BaseErrorListener printError = new BaseErrorListener() {
      @Override
      public void syntaxError(final Recognizer<?, ?> recognizer, final Object offendingSymbol,
          final int line, final int position, final String msg,
          final RecognitionException e) {
        out.println(gname + "::" + startRule + ":" + line + ":" + position + ": " + msg);
      }
    };

    ANTLRInputStream input = new ANTLRInputStream(inputText);
    LexerInterpreter lexEngine = (lg == null? g : lg).createLexerInterpreter(input);

    lexEngine.removeErrorListeners();
    lexEngine.addErrorListener(printError);

    CommonTokenStream tokens = new CommonTokenStream(lexEngine);

    ParserInterpreter parser = g.createParserInterpreter(tokens);
    parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);
    parser.removeErrorListeners();
    parser.addErrorListener(printError);
    Rule start = g.getRule(startRule);
    ParseTree tree = parser.parse(start.index);

    // this loop works around a bug in ANTLR 4.2
    // https://github.com/antlr/antlr4/issues/461
    // https://github.com/antlr/intellij-plugin-v4/issues/23
    while (tree.getParent() != null) {
      tree = tree.getParent();
    }
    String sexpression = toStringTree(tree, Arrays.asList(parser.getRuleNames())).trim();

    return sexpression;
  }

  /** Same as loadGrammar(fileName) except import vocab from existing lexer */
  private Grammar loadGrammar(final Tool tool, final String fileName,
      final LexerGrammar lexerGrammar) {
    GrammarRootAST grammarRootAST = tool.parseGrammar(fileName);
    final Grammar g = tool.createGrammar(grammarRootAST);
    g.fileName = fileName;
    if (lexerGrammar != null) {
      g.importVocab(lexerGrammar);
    }
    tool.process(g, false);
    return g;
  }

  private String toStringTree(@NonNull final Tree t, @Nullable final List<String> ruleNames) {
    if (t.getChildCount() == 0) {
      return Utils.escapeWhitespace(getNodeText(t, ruleNames), true);
    }
    StringBuilder buf = new StringBuilder();
    buf.append(" ( ");
    String s = Utils.escapeWhitespace(getNodeText(t, ruleNames), true);
    buf.append(s);
    buf.append(' ');
    for (int i = 0; i < t.getChildCount(); i++) {
      if (i > 0) {
        buf.append(' ');
      }
      buf.append(toStringTree(t.getChild(i), ruleNames));
    }
    buf.append(" ) ");
    return buf.toString();
  }

  private String getNodeText(@NonNull final Tree t, @Nullable final List<String> ruleNames) {
    if (ruleNames != null) {
      if (t instanceof RuleNode) {
        int ruleIndex = ((RuleNode) t).getRuleContext().getRuleIndex();
        String ruleName = ruleNames.get(ruleIndex);
        return ruleName;
      }
      else if (t instanceof ErrorNode) {
        return "<" + t.toString() + ">";
      }
      else if (t instanceof TerminalNode) {
        Token symbol = ((TerminalNode) t).getSymbol();
        if (symbol != null) {
          String s = symbol.getText();
          return "'" + s + "'";
        }
      }
    }
    // no recog for rule names
    Object payload = t.getPayload();
    if (payload instanceof Token) {
      return ((Token) payload).getText();
    }
    return t.getPayload().toString();
  }
}
