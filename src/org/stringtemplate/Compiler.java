/*
 [The "BSD licence"]
 Copyright (c) 2009 Terence Parr
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions
 are met:
 1. Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
 3. The name of the author may not be used to endorse or promote products
    derived from this software without specific prior written permission.

 THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.stringtemplate;

import java.util.*;

import org.antlr.runtime.*;

public class Compiler implements ExprParserListener {
    public static final String ATTR_NAME_REGEX = "[a-zA-Z/][a-zA-Z0-9_/]*";
    /** Given a template of length n, how much code will result?
     *  For now, let's assume n/5. Later, we can test in practice.
     */
    public static final double CODE_SIZE_FACTOR = 5.0;

    public static final Map<String, Integer> supportedOptions =
        new HashMap<String, Integer>() {
        {
            put("anchor",       Interpreter.OPTION_ANCHOR);
            put("format",       Interpreter.OPTION_FORMAT);
            put("null",         Interpreter.OPTION_NULL);
            put("separator",    Interpreter.OPTION_SEPARATOR);
            put("wrap",         Interpreter.OPTION_WRAP);
        }
    };

    public static final int NUM_OPTIONS = supportedOptions.size();

    public static final Map<String,String> defaultOptionValues =
        new HashMap<String,String>() {
        {
            put("anchor", "true");
            put("wrap",   "\n");
        }
    };

    public static Map<String, Short> funcs = new HashMap<String, Short>() {
        {
            put("first", BytecodeDefinition.INSTR_FIRST);
            put("last", BytecodeDefinition.INSTR_LAST);
            put("rest", BytecodeDefinition.INSTR_REST);
            put("trunc", BytecodeDefinition.INSTR_TRUNC);
            put("strip", BytecodeDefinition.INSTR_STRIP);
            put("trim", BytecodeDefinition.INSTR_TRIM);
            put("length", BytecodeDefinition.INSTR_LENGTH);
            put("strlen", BytecodeDefinition.INSTR_STRLEN);
            put("reverse", BytecodeDefinition.INSTR_REVERSE);
        }
    };

    StringTable strings;
    byte[] instrs;
    int ip = 0;
    Stack<Chunk> ifs = new Stack<Chunk>();
    Chunk currentChunk;
    ExprChunk enclosingChunk;
    CompiledST code;

    public Compiler() {;}
    
    public Compiler(ExprChunk enclosingChunk) {
        this.enclosingChunk = enclosingChunk;
    }
    
    protected static class TrackSubtemplate {
        String name;
        String template;
        ExprChunk enclosingChunk;
        int start;

        public TrackSubtemplate(String name, String template, ExprChunk enclosingChunk) {
            this.name = name;
            this.template = template;
            this.enclosingChunk = enclosingChunk;
        }
    }

    /** Track list of anonymous subtemplates. We need to name them
     *  here not in their eventual group because we need to generate
     *  code that references their names now.
     */
    protected Map<String, TrackSubtemplate> subtemplates =
        new HashMap<String, TrackSubtemplate>();

    public static int subtemplateCount = 0; // public for testing access

    public CompiledST compile(String template) {
        return compile(0, template, '<', '>');
    }

    public CompiledST compile(int startCharIndex, String template) {
        return compile(startCharIndex, template, '<', '>');
    }

    public CompiledST compile(int startCharIndex,
                              String template,
                              char delimiterStartChar,
                              char delimiterStopChar)
    {
        System.out.println("compile("+template+"), enclosingChunk="+enclosingChunk);
        code = new CompiledST();
        code.enclosingChunk = enclosingChunk;
        code.start = startCharIndex;
        code.stop = code.start + template.length()-1;
        code.template = template;
        strings = new StringTable();
        int initialSize = Math.max(5, (int)(template.length() / CODE_SIZE_FACTOR));
        instrs = new byte[initialSize];
        //System.out.println("compile "+template);
        List<Chunk> chunks = new Chunkifier(template, delimiterStartChar, delimiterStopChar).chunkify();
        for (Chunk chunk : chunks) {
            currentChunk = chunk;
            System.out.println("compile chunk "+chunk.text);
            //chunk.enclosingChunk = enclosingChunk;
            chunk.code = code;
            compile(chunk, delimiterStartChar, delimiterStopChar);
        }
        if ( strings!=null ) code.strings = strings.toArray();
        code.instrs = instrs;
        code.codeSize = ip;

        // We may have found embedded subtemplates; compile those too
        // and store in code
        compileSubtemplates();
        
        //code.dump();
        return code;
    }

    protected void compile(Chunk chunk, char delimiterStartChar, char delimiterStopChar) {
        if ( chunk.isExpr() ) {
            STLexer lexer = new STLexer(new ANTLRStringStream(chunk.text));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            STParser parser = new STParser(tokens, this, delimiterStartChar, delimiterStopChar);
            int firstTokenType = tokens.LA(1);

            if ( firstTokenType==STLexer.IF ) ifs.push(chunk);

            try {
                parser.stexpr(); // parse, trigger compile actions for single expr
            }
            catch (RecognitionException re) {
                throw new STRecognitionException(chunk, re);
            }

            if ( firstTokenType==STLexer.ENDIF ) ifs.pop();

            if ( !(firstTokenType==STLexer.IF ||
                   firstTokenType==STLexer.ELSE ||
                   firstTokenType==STLexer.ELSEIF ||
                   firstTokenType==STLexer.ENDIF) )
            {
                if ( parser.exprHasOptions ) gen(BytecodeDefinition.INSTR_WRITE_OPT);
                else gen(BytecodeDefinition.INSTR_WRITE);
            }
        }
        else {
            gen(BytecodeDefinition.INSTR_LOAD_STR, chunk.text);
            gen(BytecodeDefinition.INSTR_WRITE);
        }
    }

    public int defineString(String s) {
        return strings.add(s);
    }

    public static LinkedHashMap<String,FormalArgument> parseSubtemplateArg(String block) {
        LinkedHashMap<String,FormalArgument> args = null;
        /*
        String ws = "\\s*";
        String argPattern = ws+ATTR_NAME_REGEX+ws+"|";
        if ( block.matches(argPattern) ) System.out.println("matches: "+block);
        else System.out.println("doens't match "+argPattern+": "+block);
        */
        int pipe = block.indexOf('|');
        if ( pipe<0 ) return null;
        String argStr = block.substring(0,pipe+1).trim();
        String[] elems = argStr.split("[, |]");
        if ( elems.length==1 && // only allow 1 arg for now
             elems[0].matches(ATTR_NAME_REGEX) )
        {
            args = new LinkedHashMap<String,FormalArgument>();
            args.put(elems[0],null);
        }
        return args;
    }

    protected void compileSubtemplates() {
        if ( subtemplates.size()>0 ) System.out.println("subtemplates="+subtemplates);
        for (String subname : subtemplates.keySet()) {
            TrackSubtemplate s = subtemplates.get(subname);
            LinkedHashMap<String,FormalArgument> args =
                Compiler.parseSubtemplateArg(s.template);
            String t = s.template;
            if ( args!=null ) {
                int pipe = s.template.indexOf('|');
                t = s.template.substring(pipe+1);
            }
            t = t.trim();
            Compiler c = new Compiler(s.enclosingChunk);
            CompiledST sub = c.compile(s.start, t);
            sub.name = subname;
            sub.formalArguments = args;
            if ( code.compiledSubtemplates==null ) {
                code.compiledSubtemplates = new ArrayList<CompiledST>();
            }
            code.compiledSubtemplates.add(sub);
        }
    }

    // LISTEN TO PARSER

    public void map() {
        gen(BytecodeDefinition.INSTR_MAP);
    }

    public void mapAlternating(int numTemplates) {
        gen(BytecodeDefinition.INSTR_ROT_MAP, numTemplates);
    }

    public String defineAnonTemplate(Token subtemplate) {
        CommonToken t= (CommonToken)subtemplate;
        subtemplateCount++;
        String name = "_sub"+subtemplateCount;
        TrackSubtemplate s =
            new TrackSubtemplate(name, t.getText(), (ExprChunk)currentChunk);
        s.start = t.getStartIndex() + 1; // don't count '{' on left
        subtemplates.put(name,s);
        return name;

/*
        String text = subtemplate.getText();

        System.out.println("define sub template "+text+" in "+code.template);

        LinkedHashMap<String,FormalArgument> args =
            Compiler.parseSubtemplateArg(text);
        String t = text;
        if ( args!=null ) {
            int pipe = text.indexOf('|');
            t = text.substring(pipe+1);
        }
        t = t.trim();
        Compiler c = new Compiler((ExprChunk)currentChunk);
        CompiledST sub = c.compile(t);
        sub.name = name;
        sub.formalArguments = args;
        if ( code.compiledSubtemplates==null ) {
            code.compiledSubtemplates = new ArrayList<CompiledST>();
        }
        code.compiledSubtemplates.add(sub);

        return name;
         */
    }

    public void instance(Token id) {
        if ( id==null ) {
            gen(BytecodeDefinition.INSTR_NEW_IND);
        }
        else {
            gen(BytecodeDefinition.INSTR_NEW, id.getText());
        }
    }

    public void refAttr(Token id) {
        String name = id.getText();
        if ( Interpreter.predefinedAttributes.contains(name) ) {
            gen(BytecodeDefinition.INSTR_LOAD_LOCAL, name);
        }
        else {
            gen(BytecodeDefinition.INSTR_LOAD_ATTR, name);
        }
    }

    public void refProp(Token id) {
        if ( id == null) {
            gen(BytecodeDefinition.INSTR_LOAD_PROP_IND);
        }
        else {
            gen(BytecodeDefinition.INSTR_LOAD_PROP, id.getText());
        }
    }

    public void refString(Token str) {
        gen(BytecodeDefinition.INSTR_LOAD_STR, str.getText());
    }

    public void options() {
        gen(BytecodeDefinition.INSTR_OPTIONS);        
    }

    public void setOption(Token id) {
        Integer I = supportedOptions.get(id.getText());
        if ( I==null ) {
            System.err.println("no such option: "+id.getText());
            return;
        }
        gen(BytecodeDefinition.INSTR_STORE_OPTION, I);
    }

    public void defaultOption(Token id) {
        String v = defaultOptionValues.get(id.getText());
        if ( v==null ) {
            System.err.println("no def value for "+id.getText());
            return;
        }
        gen(BytecodeDefinition.INSTR_LOAD_STR, v);
    }

    public void setArg(Token arg) {
        if ( arg==null ) gen(BytecodeDefinition.INSTR_STORE_SOLE_ARG);
        else gen(BytecodeDefinition.INSTR_STORE_ATTR, arg.getText());
    }

    public void setPassThroughArg(Token arg) {
        gen(BytecodeDefinition.INSTR_SET_PASS_THRU);
    }

    public void ifExpr(Token t) {
        //System.out.println("ifExpr @ "+ip);
    }

    public void ifExprClause(Token t, boolean not) {
        //System.out.println("ifExprClause @ "+ip);
        ifs.peek().prevBranch = ip+1;
        short i = BytecodeDefinition.INSTR_BRF;
        if ( not ) i = BytecodeDefinition.INSTR_BRT;
        gen(i, -1); // write placeholder
    }

    public void elseifExpr(Token t) {
        //System.out.println("elseifExpr @ "+ip);
        ifs.peek().endRefs.add(ip+1);
        gen(BytecodeDefinition.INSTR_BR, -1); // write placeholder
        writeShort(instrs, ifs.peek().prevBranch, (short)ip);
        ifs.peek().prevBranch = -1;
    }

    public void elseifExprClause(Token t, boolean not) {
        //System.out.println("elseifExprClause of "+ifs.peek()+" @ "+ip);
        ifs.peek().prevBranch = ip+1;
        short i = BytecodeDefinition.INSTR_BRF;
        if ( not ) i = BytecodeDefinition.INSTR_BRT;
        gen(i, -1); // write placeholder
    }

    public void elseClause() {
        //System.out.println("else of "+ifs.peek());
        ifs.peek().endRefs.add(ip+1);
        gen(BytecodeDefinition.INSTR_BR, -1); // write placeholder
        writeShort(instrs, ifs.peek().prevBranch, (short)ip);
        ifs.peek().prevBranch = -1;
    }

    public void endif() {
        if ( ifs.peek().prevBranch>=0 ) writeShort(instrs, ifs.peek().prevBranch, (short)ip);
        List<Integer> ends = ifs.peek().endRefs;
        //System.out.println("endrefs="+ends);
        for (int opnd : ends) {
            writeShort(instrs, opnd, (short)ip);
        }
        //System.out.println("endif end");
    }

    public void list() { gen(BytecodeDefinition.INSTR_LIST); }

    public void add() { gen(BytecodeDefinition.INSTR_ADD); }

    public void eval() { gen(BytecodeDefinition.INSTR_TOSTR); }

    public void func(Token id) {
        Short funcBytecode = funcs.get(id.getText());
        if ( funcBytecode==null ) {
            System.err.println("no such fun: "+id);
            gen(BytecodeDefinition.INSTR_NOOP);
        }
        else {
            gen(funcBytecode);
        }
    }

    // GEN

    public void gen(short opcode) {
        ensureCapacity();
        instrs[ip++] = (byte)opcode;
    }

    public void gen(short opcode, int arg) {
        ensureCapacity();
        instrs[ip++] = (byte)opcode;
        writeShort(instrs, ip, (short)arg);
        ip += 2;
    }

    public void gen(short opcode, String s) {
        int i = defineString(s);
        gen(opcode, i);
    }

    protected void ensureCapacity() {
        if ( (ip+3) >= instrs.length ) { // ensure room for full instruction
            byte[] c = new byte[instrs.length*2];
            System.arraycopy(instrs, 0, c, 0, instrs.length);
            instrs = c;
        }
    }

    /** Write value at index into a byte array highest to lowest byte,
     *  left to right.
     */
    public static void writeShort(byte[] memory, int index, short value) {
        memory[index+0] = (byte)((value>>(8*1))&0xFF);
        memory[index+1] = (byte)(value&0xFF);
    }
}
