# Start llama-server with Qwen3-VL-4B on the laptop GPU (RTX 3050 Ti, 4 GB).
# The local agent talks to it at http://127.0.0.1:8080
$Llm    = "C:\Users\trevo\llm"
$Model  = "$Llm\models\Qwen3VL-4B-Instruct-Q4_K_M.gguf"
$Mmproj = "$Llm\models\mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf"

& "$Llm\llama.cpp\llama-server.exe" `
    -m $Model --mmproj $Mmproj `
    -ngl 99 `
    -c 4096 `
    -fa on --cache-type-k q8_0 --cache-type-v q8_0 `
    --host 127.0.0.1 --port 8080
