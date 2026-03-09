<fold text='//...'>// Top level comment
// across multiple lines</fold>

class MyClass <fold text='{...}'>{
    <fold text='/*...*/'>/*
     * Multi-line block comment
     */</fold>
    void MyFunction()<fold text='{...}'>{
        if (true)<fold text='{...}'>{
            Print("Hello");
        }</fold>
    }</fold>
}</fold>

namespace MyNamespace <fold text='{...}'>{
    enum MyEnum <fold text='{...}'>{
        Value1,
        Value2
    }</fold>
}</fold>
