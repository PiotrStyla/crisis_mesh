"""Build only llama-completion from the existing patched watch llama.cpp source."""
import argparse
from pathlib import Path
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--build', type=Path, default=Path('.build'))
    p.add_argument('--cmake', default='cmake')
    p.add_argument('--ninja', default='ninja')
    p.add_argument('--cc', default=None)
    p.add_argument('--cxx', default=None)
    args = p.parse_args()
    source = args.source.resolve(strict=True)
    build = args.build.resolve()
    if source == build:
        p.error('Build directory must differ from source')
    command = [args.cmake, '-S', source.as_posix(), '-B', build.as_posix(), '-G', 'Ninja',
               '-DCMAKE_MAKE_PROGRAM='+args.ninja.replace('\\', '/'),
               '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF',
               '-DGGML_NATIVE=ON', '-DLLAMA_CURL=OFF', '-DLLAMA_BUILD_TESTS=OFF',
               '-DLLAMA_BUILD_EXAMPLES=OFF', '-DLLAMA_BUILD_SERVER=OFF',
               '-DLLAMA_BUILD_APP=OFF', '-DLLAMA_BUILD_TOOLS=ON']
    for name, value in (('C', args.cc), ('CXX', args.cxx)):
        if value:
            command.append(f'-DCMAKE_{name}_COMPILER='+value.replace('\\', '/'))
    subprocess.run(command, check=True)
    subprocess.run([args.cmake, '--build', str(build), '--target', 'llama-completion', '-j', '4'], check=True)


if __name__ == '__main__':
    main()
